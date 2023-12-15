package be.ehb.openai;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.theokanning.openai.service.OpenAiService;
import com.theokanning.openai.completion.CompletionRequest;
import com.theokanning.openai.completion.CompletionResult;
import com.microsoft.cognitiveservices.speech.CancellationDetails;
import com.microsoft.cognitiveservices.speech.CancellationReason;
import com.microsoft.cognitiveservices.speech.ResultReason;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechRecognitionResult;
import com.microsoft.cognitiveservices.speech.SpeechRecognizer;
import com.microsoft.cognitiveservices.speech.SpeechSynthesisCancellationDetails;
import com.microsoft.cognitiveservices.speech.SpeechSynthesisResult;
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;

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

            Future<SpeechRecognitionResult> recognitionTask = recognizeFromMicrophone(speechConfig);

            // Wait for the recognition task to complete
            SpeechRecognitionResult speechRecognitionResult = recognitionTask.get();

            if (speechRecognitionResult.getReason() == ResultReason.RecognizedSpeech) {
                prompt = speechRecognitionResult.getText();

                // Close the speechRecognizer object
                if (speechRecognizer != null) {
                    speechRecognizer.stopContinuousRecognitionAsync();
                    speechRecognizer.close();
                    speechRecognizer = null;
                }

                return ResponseEntity.ok(prompt);
            } else {
                // Close the speechRecognizer object
                if (speechRecognizer != null) {
                    speechRecognizer.stopContinuousRecognitionAsync();
                    speechRecognizer.close();
                    speechRecognizer = null;
                }

                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
            }
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

    /*
    @PostMapping("/startRecording")
    public ResponseEntity<String> startRecording() {
        try {
            SpeechConfig speechConfig = SpeechConfig.fromSubscription(speechKey, speechRegion);
            speechConfig.setSpeechRecognitionLanguage("en-US");

            SpeechRecognitionResult speechRecognitionResult = recognizeFromMicrophone(speechConfig);

            if (speechRecognitionResult.getReason() == ResultReason.RecognizedSpeech) {
                String prompt = speechRecognitionResult.getText();
                return ResponseEntity.ok(prompt);
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
            }
        } catch (Exception e) {
            logger.error("Failed to start recording", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    private SpeechRecognitionResult recognizeFromMicrophone(SpeechConfig speechConfig) throws InterruptedException, ExecutionException {
        AudioConfig audioConfig = AudioConfig.fromDefaultMicrophoneInput();
        SpeechRecognizer recognizer = new SpeechRecognizer(speechConfig, audioConfig);

        logger.info("Speak into your microphone.");

        Future<SpeechRecognitionResult> task = recognizer.recognizeOnceAsync();
        return task.get();
    }
     */

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

    private Future<SpeechRecognitionResult> recognizeFromMicrophone(SpeechConfig speechConfig) {
        AudioConfig audioConfig = AudioConfig.fromDefaultMicrophoneInput();
        speechRecognizer = new SpeechRecognizer(speechConfig, audioConfig);

        logger.info("Speak into your microphone.");
        return speechRecognizer.recognizeOnceAsync();
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

    private static void displayModelCreationInfo(String modelId, long timestamp) {
        Instant instant = Instant.now();

        DateTimeFormatter formatter = DateTimeFormatter
                .ofLocalizedDateTime( FormatStyle.SHORT )
                .withLocale( Locale.FRANCE )
                .withZone( ZoneId.systemDefault() );
        String formattedDate = formatter.format(instant);

        logger.info("Model ID=%s is created at %s.%n", modelId, formattedDate);
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