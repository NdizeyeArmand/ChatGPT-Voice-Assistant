package be.ehb.openai;

import be.ehb.azureOpenai.Chatbot;
import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import com.theokanning.openai.completion.CompletionRequest;
import com.theokanning.openai.completion.CompletionResult;
import com.theokanning.openai.service.OpenAiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;

public class App 
{
    private static final String speechKey = System.getenv("SPEECH_KEY");
    private static final String speechRegion = System.getenv("SPEECH_REGION");
    private static final String apiKey = System.getenv("OPENAI_KEY");

    private static final Logger logger = LoggerFactory.getLogger(Chatbot.class);
    private static byte[] audioData;
    private static String prompt;
    private static String response;
    private static SpeechRecognizer speechRecognizer;
    private static SpeechSynthesizer speechSynthesizer;
    private static SpeechSynthesisResult result;
    private static Semaphore stopTranslationWithFileSemaphore;

    public static void main(String[] args) {
        SpringApplication.run(Chatbot.class, args);
    }

    @PostMapping("/startRecording")
    public ResponseEntity<String> startRecording() {
        try {
            // First initialize the semaphore.
            init();

            SpeechConfig speechConfig = SpeechConfig.fromSubscription(speechKey, speechRegion);
            String speechRecognitionLanguage = "en-US";
            speechConfig.setSpeechRecognitionLanguage(speechRecognitionLanguage);

            // Start the recognition task
            AudioConfig audioConfig = AudioConfig.fromDefaultMicrophoneInput();
            speechRecognizer = new SpeechRecognizer(speechConfig, audioConfig);

            logger.info("Speak into your microphone.");
            speechRecognizer.startContinuousRecognitionAsync().get();

            return ResponseEntity.ok(prompt);
        } catch (Exception e) {
            logger.error("Failed to start recording", e);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @PostMapping("/stopRecording")
    public ResponseEntity<String> stopRecording() {
        if (speechRecognizer != null) {
            try {
                // Waits for completion.
                stopTranslationWithFileSemaphore.acquire();

                // Stops recognition.
                speechRecognizer.stopContinuousRecognitionAsync().get();

                return ResponseEntity.ok("Recording stopped successfully");
            } catch (InterruptedException | ExecutionException e) {
                logger.error("Failed to stop recording", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to stop recording");
            }
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("No recording is currently in progress");
    }

    @PostMapping("/startSynthesizing")
    public ResponseEntity<Map<String, Object>> startSynthesizing() {
        try {
            response = queryChatbot(prompt);
            textToSpeech(response);

            // Create a custom response object
            Map<String, Object> responseObject = new HashMap<>();
            responseObject.put("audioFile", audioData);
            responseObject.put("chatbotResponse", response);

            return ResponseEntity.ok(responseObject);
        } catch (Exception e) {
            logger.error("Failed to start synthesizing", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @PostMapping("/stopSynthesizing")
    public ResponseEntity<String> stopSynthesizing() {
        if (speechSynthesizer != null) {
            try {
                speechSynthesizer.close();
                speechSynthesizer = null;
                return ResponseEntity.ok("Synthesizing stopped successfully");
            } catch (Exception e) {
                logger.error("Failed to stop synthesizing", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
            }
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("No synthesis is currently in progress");
    }

    private static String queryChatbot(String question) {

        OpenAiService service = new OpenAiService(apiKey);
        
        CompletionRequest request = CompletionRequest.builder()
            .prompt(question)
            .model("text-davinci-003")
            .maxTokens(300)
            .build();
        CompletionResult response = service.createCompletion(request); String generatedText = response.getChoices().get(0).getText();
        return generatedText;
    }

    private void init() {
        stopTranslationWithFileSemaphore = new Semaphore(0);

        speechRecognizer.recognizing.addEventListener((s, e) -> {
            prompt = e.getResult().getText();
        });

        speechRecognizer.recognized.addEventListener((s, e) -> {
            if (e.getResult().getReason() == ResultReason.RecognizedSpeech) {
                logger.info("RECOGNIZED: Text=" + e.getResult().getText());
            }
            else if (e.getResult().getReason() == ResultReason.NoMatch) {
                logger.info("NOMATCH: Speech could not be recognized.");
            }
        });

        speechRecognizer.canceled.addEventListener((s, e) -> {
            System.out.println("CANCELED: Reason=" + e.getReason());

            if (e.getReason() == CancellationReason.Error) {
                logger.error("CANCELED: ErrorCode=" + e.getErrorCode());
                logger.error("CANCELED: ErrorDetails=" + e.getErrorDetails());
                logger.error("CANCELED: Did you set the speech resource key and region values?");
            }

            stopTranslationWithFileSemaphore.release();
        });

        speechRecognizer.sessionStopped.addEventListener((s, e) -> {
            logger.info("\n    Session stopped event.");
            stopTranslationWithFileSemaphore.release();
        });
    }

    private static void textToSpeech(String text) {
        SpeechConfig speechConfig = SpeechConfig.fromSubscription(speechKey, speechRegion);
        String speechSynthesisVoiceName = "en-US-DavisNeural";
        speechConfig.setSpeechSynthesisVoiceName(speechSynthesisVoiceName);
        speechSynthesizer = new SpeechSynthesizer(speechConfig);

        if (text.isEmpty()) {
            logger.info("Text is empty");
        }

        speechSynthesizer.Synthesizing.addEventListener((o, e) -> {
            result = e.getResult();
            audioData = result.getAudioData();
            result.close();
        });

        if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
            logger.info("Speech synthesized to speaker for text [" + text + "]");
        } else if (result.getReason() == ResultReason.Canceled) {
            SpeechSynthesisCancellationDetails cancellation = SpeechSynthesisCancellationDetails.fromResult(result);
            logger.info("CANCELED: Reason=" + cancellation.getReason());

        if (cancellation.getReason() == CancellationReason.Error) {
            logger.info("CANCELED: ErrorCode=" + cancellation.getErrorCode());
            logger.info("CANCELED: ErrorDetails=" + cancellation.getErrorDetails());
            logger.info("CANCELED: Did you set the speech resource key and region values?");
            }
        }
    }
}