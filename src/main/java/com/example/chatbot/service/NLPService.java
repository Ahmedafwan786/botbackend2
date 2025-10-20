     package com.example.chatbot.service;

     import org.springframework.stereotype.Service;
     import java.util.HashMap;
     import java.util.Map;

     @Service
     public class NLPService {

         // State management (keep this)
         private Map<String, String> userStates = new HashMap<>();
         private static final String DEFAULT_USER = "default_user";

         public String generateResponse(String userInput) {
             return generateResponse(userInput, DEFAULT_USER);
         }

         public String generateResponse(String userInput, String userId) {
             if (userInput == null || userInput.trim().isEmpty()) {
                 return "Please say something so I can help.";
             }

             String lowerInput = userInput.toLowerCase();
             String state = userStates.getOrDefault(userId, "none");

             // Basic keyword-based classification (no OpenNLP)
             String category = classifyBasic(lowerInput);

             if (category.equals("symptom") && lowerInput.contains("fever")) {
                 if (!state.equals("waiting_for_fever_details")) {
                     userStates.put(userId, "waiting_for_fever_details");
                     return "I see you have a fever. How many days have you had it?";
                 } else {
                     // Parse days (simple)
                     int days = parseDaysSimple(lowerInput);
                     userStates.put(userId, "none");
                     return getBasicAdvice("fever", days);
                 }
             }

             // Default responses
             switch (category) {
                 case "greeting": return "Hello! How can I help?";
                 case "symptom": return "Tell me more about your symptoms.";
                 default: return "I'm sorry, I didn't understand.";
             }
         }

         private String classifyBasic(String input) {
             if (input.contains("hello") || input.contains("hi")) return "greeting";
             if (input.contains("fever") || input.contains("headache")) return "symptom";
             return "unknown";
         }

         private int parseDaysSimple(String input) {
             // Simple regex for numbers
             java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\d+");
             java.util.regex.Matcher m = p.matcher(input);
             if (m.find()) return Integer.parseInt(m.group());
             return 1; // Default
         }

         private String getBasicAdvice(String symptom, int days) {
             return "For " + symptom + " lasting " + days + " days, rest and see a doctor if needed.";
         }
     }
     
