package com.example.chatbot.service;

import opennlp.tools.doccat.*;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.PlainTextByLineStream;
import opennlp.tools.util.Span;
import opennlp.tools.util.TrainingParameters;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NLPService {

    private static final String MODEL_DIR = System.getProperty("user.dir") + File.separator + "models";
    private static final String DOCCAT_MODEL_FILE = MODEL_DIR + File.separator + "en-doccat.bin";
    private static final String TOKENIZER_MODEL_FILE = "/models/en-token.bin";
    private static final String NER_MODEL_FILE = "/models/en-ner-person.bin";

    private DocumentCategorizerME categorizer;
    private TokenizerME tokenizer;
    private NameFinderME nameFinder;

    // State management: key=userId, value=state (e.g., "waiting_for_fever_details_1")
    private Map<String, String> userStates = new HashMap<>();
    private static final String DEFAULT_USER = "default_user";

    @PostConstruct
    public void init() {
        try {
            Path modelDirPath = Paths.get(MODEL_DIR);
            if (!Files.exists(modelDirPath)) {
                Files.createDirectories(modelDirPath);
            }

            // Load tokenizer with fallback
            try (InputStream tokenizerModelIn = getClass().getResourceAsStream(TOKENIZER_MODEL_FILE)) {
                if (tokenizerModelIn == null) {
                    System.err.println("Warning: Tokenizer model not found at " + TOKENIZER_MODEL_FILE + ". Using basic tokenization.");
                    tokenizer = null;
                } else {
                    TokenizerModel tokenizerModel = new TokenizerModel(tokenizerModelIn);
                    tokenizer = new TokenizerME(tokenizerModel);
                    System.out.println("Tokenizer loaded successfully.");
                }
            } catch (Exception e) {
                System.err.println("Error loading tokenizer: " + e.getMessage());
                tokenizer = null;
            }

            // Load NER model (optional, for entity extraction)
            try (InputStream nerModelIn = getClass().getResourceAsStream(NER_MODEL_FILE)) {
                if (nerModelIn != null) {
                    TokenNameFinderModel nerModel = new TokenNameFinderModel(nerModelIn);
                    nameFinder = new NameFinderME(nerModel);
                    System.out.println("NER model loaded successfully.");
                } else {
                    System.out.println("NER model not found; using keyword detection only.");
                    nameFinder = null;
                }
            } catch (Exception e) {
                System.err.println("Error loading NER model: " + e.getMessage());
                nameFinder = null;
            }

            // Load or train doccat model (critical for classification)
            try {
                File doccatModelFile = new File(DOCCAT_MODEL_FILE);
                if (doccatModelFile.exists()) {
                    try (InputStream modelIn = new FileInputStream(doccatModelFile)) {
                        DoccatModel model = new DoccatModel(modelIn);
                        categorizer = new DocumentCategorizerME(model);
                        System.out.println("Categorizer loaded from disk.");
                    }
                } else {
                    System.out.println("No categorizer model found. Training from training.txt...");
                    try (InputStream dataIn = getClass().getResourceAsStream("/training.txt")) {
                        if (dataIn == null) {
                            throw new RuntimeException("training.txt not found in resources. Cannot train model.");
                        }
                        ObjectStream<String> lineStream = new PlainTextByLineStream(() -> dataIn, StandardCharsets.UTF_8);
                        ObjectStream<DocumentSample> sampleStream = new DocumentSampleStream(lineStream);

                        TrainingParameters params = new TrainingParameters();
                        params.put(TrainingParameters.ITERATIONS_PARAM, "100");
                        params.put(TrainingParameters.CUTOFF_PARAM, "1");

                        DoccatModel model = DocumentCategorizerME.train("en", sampleStream, params, new DoccatFactory());

                        try (OutputStream modelOut = new BufferedOutputStream(new FileOutputStream(DOCCAT_MODEL_FILE))) {
                            model.serialize(modelOut);
                        }

                        categorizer = new DocumentCategorizerME(model);
                        System.out.println("Categorizer trained and saved.");
                    }
                }
            } catch (Exception e) {
                System.err.println("Error with categorizer: " + e.getMessage());
                categorizer = null;
            }
        } catch (Exception e) {
            System.err.println("Critical error in NLPService init: " + e.getMessage());
            e.printStackTrace();
            // Allow app to start with limited functionality
        }
    }

    public String generateResponse(String userInput) {
        return generateResponse(userInput, DEFAULT_USER);
    }

    public String generateResponse(String userInput, String userId) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return "Please say something so I can help.";
        }

        String lowerInput = userInput.toLowerCase();
        String[] tokens = (tokenizer != null) ? tokenizer.tokenize(lowerInput) : lowerInput.split("\\s+");

        String state = userStates.getOrDefault(userId, "none");
        
    // Handle waiting states for symptom details
   if (state.startsWith("waiting_for_")) {
       String[] parts = state.split("_");
       if (parts.length >= 5) {  // Ensure the state has enough parts
           String symptom = parts[2];  // e.g., "fever"
           int retry = 1;  // Default retry count
           try {
               retry = Integer.parseInt(parts[4]);  // parts[4] is the retry number (e.g., "1")
           } catch (NumberFormatException e) {
               System.err.println("Invalid retry count in state: " + state + ". Resetting to default.");
               retry = 1;
           }

           ParsedDetails details = parseSymptomDetails(lowerInput);
           if (details.days >= 0) {
               userStates.put(userId, "none");
               return getSymptomAdvice(symptom, details);
           } else if (retry < 2) {
               userStates.put(userId, "waiting_for_" + symptom.replace(" ", "_") + "_details_" + (retry + 1));
               return "I didn't catch that. Please tell me how many days you've had the " + symptom.replace("_", " ") + " (e.g., '3 days', 'yesterday', 'today', or 'morning'). Is it mild or severe?";
           } else {
               userStates.put(userId, "none");
               return "I'm having trouble understanding. For now, rest and monitor your symptoms. If they persist, see a doctor.";
           }
       } else {
           // Malformed state, reset to avoid loops
           userStates.put(userId, "none");
           return "Sorry, I lost track. Can you tell me about your symptoms again?";
       }
   }
   

        // Classify input if categorizer is available
        String category = "unknown";
        double confidence = 0.0;
        if (categorizer != null) {
            double[] outcomes = categorizer.categorize(tokens);
            category = categorizer.getBestCategory(outcomes);
            confidence = Arrays.stream(outcomes).max().orElse(0.0);
        } else {
            // Fallback to basic classification
            category = classifyBasic(tokens);
        }

        System.out.println("Input: " + userInput + " | Category: " + category + " | Confidence: " + confidence);

        if (confidence < 0.5 && categorizer != null) {
            return "I'm sorry, I didn't understand that. Can you rephrase or provide more details?";
        }

        switch (category) {
            case "greeting":
                return "Hello! I'm your health assistant. How can I help you today?";
            case "thanks":
                return "You're welcome—glad to help!";
            case "goodbye":
                return "Goodbye! Take care and feel better soon.";
            case "symptom":
                List<String> symptoms = extractSymptoms(lowerInput, tokens);
                if (!symptoms.isEmpty()) {
                    String primarySymptom = symptoms.get(0);
                    userStates.put(userId, "waiting_for_" + primarySymptom.replace(" ", "_") + "_details_1");
                    String multipleNote = symptoms.size() > 1 ? " I noticed you mentioned multiple symptoms (" + String.join(", ", symptoms) + ")—let's focus on the " + primarySymptom + " first." : "";
                    return "I see you have a " + primarySymptom + "." + multipleNote + " How many days have you had it? (e.g., '3 days', 'yesterday', 'today', or 'morning'). Is it mild or severe?";
                } else {
                    return "I see—can you tell me how long you've had these symptoms and whether they're getting worse?";
                }
            case "medication":
                return "I can't prescribe medicine, but for common issues like fever or headache, consider acetaminophen or ibuprofen if appropriate. If severe, see a doctor.";
            case "advice":
                return "If symptoms are mild, rest and stay hydrated. If you have severe symptoms (chest pain, difficulty breathing, fainting), seek emergency care.";
            case "appointment":
                return "I can help book an appointment. What date and time works for you?";
            case "insurance":
                return "For billing and insurance questions, please contact support or provide your insurer details.";
            case "info":
                return "Our hours are Mon-Fri 9am-5pm. We're located at 123 Health St. You can call support at (555) 123-4567.";
            default:
                return "I'm sorry, I didn't understand that. Can you rephrase or provide more details?";
        }
    }

    // Extract symptoms using NER or fallback to keywords
    private List<String> extractSymptoms(String input, String[] tokens) {
        List<String> symptoms = new ArrayList<>();
        if (nameFinder != null) {
            try {
                Span[] spans = nameFinder.find(tokens);
                for (Span span : spans) {
                    String entity = String.join(" ", Arrays.copyOfRange(tokens, span.getStart(), span.getEnd()));
                    if (isSymptom(entity)) symptoms.add(entity);
                }
                nameFinder.clearAdaptiveData();
            } catch (Exception e) {
                System.err.println("NER extraction failed: " + e.getMessage());
            }
        }
        if (symptoms.isEmpty()) {
            symptoms = detectSymptomsKeywords(input);
        }
        return symptoms;
    }

    // Keyword-based symptom detection
    private List<String> detectSymptomsKeywords(String input) {
        List<String> symptoms = new ArrayList<>();
        Map<String, String> symptomMap = Map.ofEntries(
            Map.entry("fever", "fever"),
            Map.entry("headache", "headache"),
            Map.entry("cough", "cough"),
            Map.entry("stomach", "nausea"),
            Map.entry("nauseous", "nausea"),
            Map.entry("dizzy", "dizziness"),
            Map.entry("sore throat", "sore throat"),
            Map.entry("back pain", "back pain"),
            Map.entry("shortness of breath", "shortness of breath"),
            Map.entry("chest pain", "chest pain"),
            Map.entry("fatigue", "fatigue"),
            Map.entry("body aches", "body aches"),
            Map.entry("runny nose", "runny nose"),
            Map.entry("joint pain", "joint pain")
        );
        for (Map.Entry<String, String> entry : symptomMap.entrySet()) {
            if (input.contains(entry.getKey())) symptoms.add(entry.getValue());
        }
        return symptoms;
    }

    // Check if entity is a symptom
    private boolean isSymptom(String entity) {
        return detectSymptomsKeywords(entity.toLowerCase()).contains(entity.toLowerCase());
    }

    // Basic classification fallback
    private String classifyBasic(String[] tokens) {
        String input = String.join(" ", tokens).toLowerCase();
        if (input.contains("hello") || input.contains("hi") || input.contains("hey") || input.contains("good morning")) return "greeting";
        if (input.contains("thank") || input.contains("appreciate")) return "thanks";
        if (input.contains("bye") || input.contains("goodbye")) return "goodbye";
        if (input.contains("fever") || input.contains("headache") || input.contains("cough") || input.contains("pain")) return "symptom";
        if (input.contains("medicine") || input.contains("ibuprofen")) return "medication";
        if (input.contains("doctor") || input.contains("advice")) return "advice";
        if (input.contains("appointment") || input.contains("book")) return "appointment";
        if (input.contains("insurance") || input.contains("billing")) return "insurance";
        if (input.contains("hours") || input.contains("location") || input.contains("contact")) return "info";
        return "unknown";
    }

    // Enhanced parsing for duration, severity, escalation (includes "morning", "today", "yesterday")
    private ParsedDetails parseSymptomDetails(String input) {
        int days = -1;
        String severity = "mild";
        boolean escalating = false;

        // Duration patterns: numbers, "a week", "since yesterday", "yesterday" (1), "today" (0), "morning" (0)
        Pattern dayPattern = Pattern.compile("(\\d+|a week|since yesterday|yesterday|today|morning)");
        Matcher dayMatcher = dayPattern.matcher(input);
        if (dayMatcher.find()) {
            String match = dayMatcher.group(1);
            switch (match) {
                case "a week": days = 7; break;
                case "since yesterday": days = 1; break;
                case "yesterday": days = 1; break;
                case "today": days = 0; break;
                case "morning": days = 0; break;
                default: days = Integer.parseInt(match); break;
            }
        }

        if (input.contains("severe") || input.contains("bad") || input.contains("terrible") || input.contains("intense")) {
            severity = "severe";
        } else if (input.contains("mild") || input.contains("slight")) {
            severity = "mild";
        }

        if (input.contains("getting worse") || input.contains("worsening") || input.contains("increasing")) {
            escalating = true;
        }

        return new ParsedDetails(days, severity, escalating);
    }

    // Get advice and medicine based on symptom and details
    private String getSymptomAdvice(String symptom, ParsedDetails details) {
        String symptomDisplay = symptom.replace("_", " ");
        String baseAdvice = "";
        String medicine = "";
        if ("severe".equals(details.severity) || details.days >= 3 || details.escalating) {
            baseAdvice = "Since it's " + details.severity + " and has lasted " + details.days + " days" + (details.escalating ? " and is getting worse" : "") + ", please see a doctor immediately for evaluation. ";
        } else {
            baseAdvice = "For a " + details.severity + " " + symptomDisplay + " lasting " + details.days + " days, ";
        }

        switch (symptom) {
            case "fever":
                medicine = "consider acetaminophen or ibuprofen if appropriate.";
                return baseAdvice + "rest, hydrate, and " + medicine + " Monitor your temperature.";
            case "headache":
                medicine = "consider over-the-counter pain relievers like ibuprofen if needed.";
                return baseAdvice + "try rest, hydration, and " + medicine;
            case "cough":
                medicine = "consider cough syrup or lozenges if needed.";
                return baseAdvice + "stay hydrated, use honey for soothing, avoid irritants, and " + medicine;
            case "nausea":
                medicine = "consider anti-nausea meds like dimenhydrinate if needed.";
                return baseAdvice + "avoid heavy foods, drink ginger tea, and rest. " + medicine;
            case "dizziness":
                medicine = "consider antihistamines if allergies are suspected.";
                return baseAdvice + "sit down, hydrate, avoid sudden movements, and " + medicine;
            case "sore throat":
                medicine = "consider lozenges or throat sprays.";
                return baseAdvice + "gargle with salt water, stay hydrated, and " + medicine;
            case "back pain":
                medicine = "consider anti-inflammatory meds like ibuprofen.";
                return baseAdvice + "apply ice/heat, rest, gentle stretches, and " + medicine;
            case "shortness of breath":
                medicine = "use prescribed inhalers if applicable.";
                return baseAdvice + "sit upright, use a fan, and seek immediate medical help if severe. " + medicine;
            case "chest pain":
                medicine = "chew aspirin if no allergies.";
                return baseAdvice + "stop activity, " + medicine + ", and call emergency services.";
            case "fatigue":
                medicine = "consider energy supplements or consult for underlying causes.";
                return baseAdvice + "get plenty of rest, eat balanced meals, and " + medicine;
            case "body aches":
                medicine = "consider anti-inflammatory meds like ibuprofen.";
                return baseAdvice + "rest, apply heat, and " + medicine;
            case "runny nose":
                medicine = "consider antihistamines or decongestants.";
                return baseAdvice + "use saline sprays, stay hydrated, and " + medicine;
            case "joint pain":
                medicine = "consider anti-inflammatory meds like ibuprofen.";
                return baseAdvice + "apply ice/heat, rest the joint, and " + medicine;
            default:
                return baseAdvice + "rest and monitor your symptoms.";
        }
    }

    private static class ParsedDetails {
        int days;
        String severity;
        boolean escalating;

        ParsedDetails(int days, String severity, boolean escalating) {
            this.days = days;
            this.severity = severity;
            this.escalating = escalating;
        }
    }
}
