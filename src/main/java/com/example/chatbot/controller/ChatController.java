package com.example.chatbot.controller;

import com.example.chatbot.service.NLPService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.regex.*;
import java.util.*;

@RestController
@RequestMapping("/chat")
@CrossOrigin(origins = "https://ahmedafwan786.github.io")
public class ChatController {

    @Autowired
    private NLPService nlpService;

    // To remember previous user intent
    private String lastIntent = "";

    // Map of number words to digits
    private static final Map<String, Integer> numberWords = Map.ofEntries(
            Map.entry("one", 1),
            Map.entry("two", 2),
            Map.entry("three", 3),
            Map.entry("four", 4),
            Map.entry("five", 5),
            Map.entry("six", 6),
            Map.entry("seven", 7)
    );

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String message = request.getMessage().toLowerCase();
        String intent = nlpService.detectIntent(message);
        String reply;

        if (intent.equals("greeting")) {
            reply = "Hello! I'm your health assistant. How can I help you today?";
            lastIntent = "greeting";

        } else if (intent.equals("symptom")) {
            reply = "I see — can you tell me how long you've had these symptoms and whether they're getting worse?";
            lastIntent = "symptom";

        } else if (lastIntent.equals("symptom") && containsDaysInfo(message)) {
            // Extract duration
            int days = extractDays(message);

            if (days >= 3) {
                reply = "Since you've had symptoms for " + days + " days, you may take **Paracetamol (500mg)** every 6–8 hours for fever, stay hydrated, and rest well. "
                      + "If the fever or pain continues beyond another 2 days, please see a doctor for proper diagnosis.";
            } else if (days == 1 || days == 2) {
                reply = "It's been " + days + " day" + (days > 1 ? "s" : "") + ". "
                      + "For now, rest, drink fluids, and take **Paracetamol (500mg)** if needed. Monitor your temperature regularly.";
            } else {
                reply = "Okay, since symptoms just started, monitor them closely. If they get worse or you develop new symptoms, consult a doctor.";
            }

            lastIntent = "advice";

        } else if (intent.equals("advice")) {
            reply = "If your symptoms persist or worsen, it’s best to consult a healthcare professional.";
            lastIntent = "advice";

        } else if (intent.equals("goodbye")) {
            reply = "Take care! I hope you feel better soon.";
            lastIntent = "goodbye";

        } else {
            // Fallbacks with some context awareness
            if (lastIntent.equals("symptom")) {
                reply = "Could you please tell me how long you've had these symptoms? (e.g., for 3 days)";
            } else {
                reply = "I'm here to help. Can you tell me what symptoms you’re having?";
            }
        }

        return new ChatResponse(reply);
    }

    // --- Helper functions ---

    private boolean containsDaysInfo(String message) {
        return message.matches(".*\\b(\\d+|one|two|three|four|five|six|seven)\\b.*day.*");
    }

    private int extractDays(String message) {
        // Try numeric match first
        Matcher matcher = Pattern.compile("(\\d+)\\s*day").matcher(message);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }

        // Try number word match
        for (Map.Entry<String, Integer> entry : numberWords.entrySet()) {
            if (message.contains(entry.getKey() + " day")) {
                return entry.getValue();
            }
        }

        // Default if no match
        return 0;
    }

    // --- DTO classes ---
    public static class ChatRequest {
        private String message;
        public ChatRequest() {}
        public ChatRequest(String message) { this.message = message; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    public static class ChatResponse {
        private String reply;
        public ChatResponse() {}
        public ChatResponse(String reply) { this.reply = reply; }
        public String getReply() { return reply; }
        public void setReply(String reply) { this.reply = reply; }
    }
}
