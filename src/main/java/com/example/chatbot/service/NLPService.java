package com.example.chatbot.service;

import opennlp.tools.doccat.DoccatFactory;
import opennlp.tools.doccat.DoccatModel;
import opennlp.tools.doccat.DocumentCategorizerME;
import opennlp.tools.doccat.DocumentSample;
import opennlp.tools.doccat.DocumentSampleStream;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.PlainTextByLineStream;
import opennlp.tools.util.TrainingParameters;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class NLPService {

    private static final String MODEL_DIR = System.getProperty("user.dir") + File.separator + "models";
    private static final String MODEL_FILE = MODEL_DIR + File.separator + "en-doccat.bin";

    private DocumentCategorizerME categorizer;

    @PostConstruct
    public void init() {
        try {
            // Ensure model directory exists
            Path modelDirPath = Paths.get(MODEL_DIR);
            if (!Files.exists(modelDirPath)) {
                Files.createDirectories(modelDirPath);
            }

            // If model file exists on filesystem, load it. Else train from bundled training.txt.
            File modelFile = new File(MODEL_FILE);
            if (modelFile.exists()) {
                try (InputStream modelIn = new FileInputStream(modelFile)) {
                    DoccatModel model = new DoccatModel(modelIn);
                    categorizer = new DocumentCategorizerME(model);
                    System.out.println("Loaded existing model from " + modelFile.getAbsolutePath());
                }
            } else {
                System.out.println("No model found on disk. Training model from bundled training data...");
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
                    // persist model to disk
                    try (OutputStream modelOut = new BufferedOutputStream(new FileOutputStream(MODEL_FILE))) {
                        model.serialize(modelOut);
                    }
                    categorizer = new DocumentCategorizerME(model);
                    System.out.println("Training complete. Saved model to " + MODEL_FILE);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize NLPService", e);
        }
    }

    public String generateResponse(String userInput) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return "Please say something so I can help.";
        }
        double[] outcomes = categorizer.categorize(userInput);
        String category = categorizer.getBestCategory(outcomes);

        switch (category) {
            case "greeting":
                return "Hello! I'm your health assistant. How can I help you today?";
            case "symptom":
                return "I see — can you tell me how long you've had these symptoms and whether they're getting worse?";
            case "medication":
                return "I can't prescribe medicine, but for common fevers you can consider acetaminophen or ibuprofen if appropriate. If severe, see a doctor.";
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
}
