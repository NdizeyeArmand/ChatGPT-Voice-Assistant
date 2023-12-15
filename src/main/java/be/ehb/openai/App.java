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

public class App 
{
    private static final String speechKey = System.getenv("SPEECH_KEY");
    private static final String speechRegion = System.getenv("SPEECH_REGION");
    private static final String apiKey = System.getenv("OPENAI_KEY");

    private static final Logger logger = LoggerFactory.getLogger(Chatbot.class);
    private static String prompt;
    private static SpeechRecognizer speechRecognizer;
    private static SpeechSynthesizer speechSynthesizer;

    public static void main(String[] args) {
        SpringApplication.run(Chatbot.class, args);
    }

    /*
    @GetMapping("/logs")
    public String getLogs() {
        // Capture the logs in a ByteArrayOutputStream
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
        logger.info("Fetching logs...");
        logger.info("Additional log statements if needed");

        // Redirect System.out to the ByteArrayOutputStream
        PrintStream oldOut = System.out;
        System.setOut(ps);

        // Print the logs
        logger.info("Printing logs...");
        logger.warn("This is a warning log");
        logger.error("This is an error log");

        // Restore System.out
        System.out.flush();
        System.setOut(oldOut);

        // Get the logs from the ByteArrayOutputStream as a string
        String logs = baos.toString();

        // Return the logs as part of the API response
        return logs;
    }

    @GetMapping("/health")
    public ResponseEntity<String> checkHealth() {
        // Implement your health check logic here
        boolean isHealthy = true;

        if (isHealthy) {
            return ResponseEntity.ok("Healthy");
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Unhealthy");
        }
    }
    */

    @PostMapping("/startRecording")
    public ResponseEntity<String> startRecording() {
        try {
            SpeechConfig speechConfig = SpeechConfig.fromSubscription(speechKey, speechRegion);
            speechConfig.setSpeechRecognitionLanguage("en-US");

            // Start the recognition task
            recognizeFromMicrophone(speechConfig);

            return ResponseEntity.ok("Recording started successfully");
        } catch (Exception e) {
            logger.error("Failed to start recording", e);

            // Close the speechRecognizer object
            if (speechRecognizer != null) {
                speechRecognizer.stopContinuousRecognitionAsync();
                speechRecognizer.close();
                speechRecognizer = null;
            }

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @PostMapping("/stopRecording")
    public ResponseEntity<String> stopRecording() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopContinuousRecognitionAsync().get();
                speechRecognizer.close();
                speechRecognizer = null;
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
            String chatbotResponse = queryChatbot(prompt);
            byte[] audioFile = textToSpeech(chatbotResponse);

            if (speechSynthesizer != null) {
                speechSynthesizer.close();
                speechSynthesizer = null;
            }

            // Create a custom response object
            Map<String, Object> responseObject = new HashMap<>();
            responseObject.put("audioFile", audioFile);
            responseObject.put("chatbotResponse", chatbotResponse);

            return ResponseEntity.ok(responseObject);
        } catch (Exception e) {
            logger.error("Failed to start and stop synthesizing", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    private void recognizeFromMicrophone(SpeechConfig speechConfig) {
        AudioConfig audioConfig = AudioConfig.fromDefaultMicrophoneInput();
        SpeechRecognizer recognizer = new SpeechRecognizer(speechConfig, audioConfig);

        logger.info("Speak into your microphone.");

        recognizer.recognizing.addEventListener((o, speechRecognitionResultEventArgs) -> {
            logger.info("Intermediate recognition result: " + speechRecognitionResultEventArgs.getResult().getText());
        });

        recognizer.recognized.addEventListener((o, speechRecognitionResultEventArgs) -> {
            if (speechRecognitionResultEventArgs.getResult().getReason() == ResultReason.RecognizedSpeech) {
                logger.info("Final recognition result: " + speechRecognitionResultEventArgs.getResult().getText());
                recognizer.stopContinuousRecognitionAsync();
            }
        });

        recognizer.startContinuousRecognitionAsync();
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

    private static byte[] textToSpeech(String text) throws InterruptedException, ExecutionException {
        SpeechConfig speechConfig = SpeechConfig.fromSubscription(speechKey, speechRegion);
        speechConfig.setSpeechSynthesisVoiceName("en-US-DavisNeural");
        speechSynthesizer = new SpeechSynthesizer(speechConfig);

        if (text.isEmpty()) {
            return new byte[0];
        }

        SpeechSynthesisResult speechSynthesisResult = speechSynthesizer.SpeakTextAsync(text).get();

        if (speechSynthesisResult.getReason() == ResultReason.SynthesizingAudioCompleted) {
            logger.info("Speech synthesized to speaker for text [" + text + "]");
            return speechSynthesisResult.getAudioData();
        } else if (speechSynthesisResult.getReason() == ResultReason.Canceled) {
            SpeechSynthesisCancellationDetails cancellation = SpeechSynthesisCancellationDetails.fromResult(speechSynthesisResult);
            logger.info("CANCELED: Reason=" + cancellation.getReason());

            if (cancellation.getReason() == CancellationReason.Error) {
                logger.info("CANCELED: ErrorCode=" + cancellation.getErrorCode());
                logger.info("CANCELED: ErrorDetails=" + cancellation.getErrorDetails());
                logger.info("CANCELED: Did you set the speech resource key and region values?");
            }
        }
        return new byte[0];
    }
}