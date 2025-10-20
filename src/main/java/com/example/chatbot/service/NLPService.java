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
    private static final String NER_MODEL_FILE = "/models/en-ner-person.bin"; // For entity extraction (symptoms)

    private DocumentCategorizerME categorizer;
    private TokenizerME tokenizer;
    private NameFinderME nameFinder; // For entity extraction

    // State management
    private Map<String, String> userStates = new HashMap<>();
    private static final String DEFAULT_USER = "default_user";

    @PostConstruct
    public void init() {
        try {
            Path modelDirPath = Paths.get(MODEL_DIR);
            if (!Files.exists(modelDirPath)) {
                Files.createDirectories(modelDirPath);
            }

            // Load tokenizer
            try (InputStream tokenizerModelIn = getClass().getResourceAsStream(TOKENIZER_MODEL_FILE)) {
                if (tokenizerModelIn == null) {
                    throw new FileNotFoundException("Tokenizer model not found at " + TOKENIZER_MODEL_FILE);
                }
                TokenizerModel tokenizerModel = new TokenizerModel(tokenizerModelIn);
                tokenizer = new TokenizerME(tokenizerModel);
            }

            // Load NER model for entity extraction
            try (InputStream nerModelIn = getClass().getResourceAsStream(NER_MODEL_FILE)) {
                if (nerModelIn != null) {
                    TokenNameFinderModel nerModel = new TokenNameFinderModel(nerModelIn);
                    nameFinder = new NameFinderME(nerModel);
                } else {
                    System.out.println("NER model not found; falling back to keyword detection.");
                }
            }

            // Load or train doccat model
            File doccatModelFile = new File(DOCCAT_MODEL_FILE);
            if (doccatModelFile.exists()) {
                try (InputStream modelIn = new FileInputStream(doccatModelFile)) {
                    DoccatModel model = new DoccatModel(modelIn);
                    categorizer = new DocumentCategorizerME(model);
                    System.out.println("Loaded existing doccat model from " + doccatModelFile.getAbsolutePath());
                }
            } else {
                System.out.println("No doccat model found. Training from training.txt...");
                try (InputStream dataIn = getClass().getResourceAsStream("/training.txt")) {
                    if (dataIn == null) {
                        throw new FileNotFoundException("training.txt not found in resources");
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
                    System.out.println("Training complete. Saved model to " + DOCCAT_MODEL_FILE);
                }
            }
        } catch (Exception e) {
            System.err.println("Error initializing NLPService: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize NLPService", e);
        }
    }

    public String generateResponse(String userInput) {
        return generateResponse(userInput, DEFAULT_USER);
    }

    public String generateResponse(String userInput, String userId) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return "Please say something so I can help.";
        }

        String state = userStates.getOrDefault(userId, "none");
        String lowerInput = userInput.toLowerCase();

        // Handle waiting states
        if (state.startsWith("waiting_for_")) {
            String[] parts = state.split("_");
            String symptom = parts[2];
            int retry = Integer.parseInt(parts[3]);

            ParsedDetails details = parseSymptomDetails(lowerInput);
            if (details.days > 0) {
                userStates.put(userId, "none");
                return getSymptomAdvice(symptom, details);
            } else if (retry < 2) {
                userStates.put(userId, "waiting_for_" + symptom + "_details_" + (retry + 1));
                return "I didn't catch that. Please tell me how many days you've had the " + symptom.replace("_", " ") + " and if it's mild or severe (e.g., '3 days and severe').";
            } else {
                userStates.put(userId, "none");
                return "I'm having trouble understanding. For now, rest and monitor your symptoms. If they persist, see a doctor.";
            }
        }

        // Classify input
        String[] tokens = tokenizer.tokenize(lowerInput);
        double[] outcomes = categorizer.categorize(tokens);
        String category = categorizer.getBestCategory(outcomes);
        // Fixed: Get probability of best category
        Map<String, Double> allResults = categorizer.getAllResults(outcomes);
        double confidence = allResults.getOrDefault(category, 0.0);

        System.out.println("Input: " + userInput + " | Category: " + category + " | Confidence: " + confidence);

        if (confidence < 0.5) {
            return "I'm sorry, I didn't understand that. Can you rephrase or provide more details?";
        }

        switch (category) {
            case "greeting":
                return "Hello! I'm your health assistant. How can I help you today?";
            case "symptom":
                List<String> symptoms = extractSymptoms(lowerInput, tokens);
                if (!symptoms.isEmpty()) {
                    String primarySymptom = symptoms.get(0);
                    userStates.put(userId, "waiting_for_" + primarySymptom.replace(" ", "_") + "_details_1");
                    String multipleNote = symptoms.size() > 1 ? " I noticed you mentioned multiple symptoms (" + String.join(", ", symptoms) + ")—let's focus on the " + primarySymptom + " first." : "";
                    return "I see you have a " + primarySymptom + "." + multipleNote + " How many days have you had it, and is it mild or severe?";
                } else {
                    return "I see — can you tell me how long you've had these symptoms and whether they're getting worse?";
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
            case "thanks":
                return "You're welcome — glad to help!";
            case "goodbye":
                return "Goodbye! Take care and feel better soon.";
            default:
                return "I'm sorry, I didn't understand that. Can you rephrase or provide more details?";
        }
    }

    // Extract symptoms using NER or fallback to keywords
    private List<String> extractSymptoms(String input, String[] tokens) {
        List<String> symptoms = new ArrayList<>();
        if (nameFinder != null) {
            Span[] spans = nameFinder.find(tokens); // Basic NER; in practice, train a symptom-specific model
            for (Span span : spans) {
                String entity = String.join(" ", Arrays.copyOfRange(tokens, span.getStart(), span.getEnd()));
                if (isSymptom(entity)) symptoms.add(entity);
            }
            nameFinder.clearAdaptiveData(); // Reset for next input
        }
        if (symptoms.isEmpty()) {
            // Fallback to keyword detection
            symptoms = detectSymptomsKeywords(input);
        }
        return symptoms;
    }

    // Keyword-based symptom detection (expanded)
    private List<String> detectSymptomsKeywords(String input) {
        List<String> symptoms = new ArrayList<>();
        // Fixed: Use Map.ofEntries() for more than 10 pairs
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

    // Check if extracted entity is a symptom (basic filter)
    private boolean isSymptom(String entity) {
        return detectSymptomsKeywords(entity.toLowerCase()).contains(entity.toLowerCase());
    }

    // Parsing and advice methods (same as before, with added symptoms)
    private ParsedDetails parseSymptomDetails(String input) {
        int days = -1;
        String severity = "mild";
        boolean escalating = false;

        Pattern dayPattern = Pattern.compile("(\\d+|a week|since yesterday)");
        Matcher dayMatcher = dayPattern.matcher(input);
        if (dayMatcher.find()) {
            String match = dayMatcher.group(1);
            if ("a week".equals(match)) days = 7;
            else if ("since yesterday".equals(match)) days = 1;
            else days = Integer.parseInt(match);
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

    private String getSymptomAdvice(String symptom, ParsedDetails details) {
        String symptomDisplay = symptom.replace("_", " ");
        String baseAdvice = "";
        if ("severe".equals(details.severity) || details.days >= 3 || details.escalating) {
            baseAdvice = "Since it's " + details.severity + " and has lasted " + details.days + " days" + (details.escalating ? " and is getting worse" : "") + ", please see a doctor immediately for evaluation. ";
        } else {
            baseAdvice = "For a " + details.severity + " " + symptomDisplay + " lasting " + details.days + " days, ";
        }

        switch (symptom) {
            case "fever":
                return baseAdvice + "rest, hydrate, and consider acetaminophen. Monitor your temperature.";
            case "headache":
                return baseAdvice + "try rest, hydration, and over-the-counter pain relievers like ibuprofen if needed.";
            case "cough":
                return baseAdvice + "stay hydrated, use honey for soothing, and avoid irritants.";
            case "nausea":
                return baseAdvice + "avoid heavy foods, drink ginger tea, and rest.";
            case "dizziness":
                return baseAdvice + "sit down, hydrate, and avoid sudden movements.";
            case "sore throat":
                return baseAdvice + "gargle with salt water, stay hydrated, and use lozenges.";
            case "back pain":
                return baseAdvice + "apply ice/heat, rest, and gentle stretches.";
            case "shortness of breath":
                return baseAdvice + "sit upright, use a fan, and seek immediate medical help if severe.";
            case "chest pain":
                return baseAdvice + "stop activity, chew aspirin if no allergies, and call emergency services.";
            case "fatigue":
                return baseAdvice + "get plenty of rest, eat balanced meals, and check for underlying causes like anemia.";
            case "body aches":
                return baseAdvice + "rest, apply heat, and consider anti-inflammatory meds.";
            case "runny nose":
                return baseAdvice + "use saline sprays, stay hydrated, and rest.";
            case "joint pain":
                return baseAdvice + "apply ice/heat, rest the joint, and consider gentle exercises.";
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
