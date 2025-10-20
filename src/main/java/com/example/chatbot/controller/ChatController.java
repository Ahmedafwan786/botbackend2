package com.example.chatbot.controller;

import com.example.chatbot.service.NLPService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat")
@CrossOrigin(origins = "https://ahmedafwan786.github.io")
public class ChatController {

    @Autowired
    private NLPService nlpService;

    // Keep track of last intent for simple conversational context
    private String lastIntent = "";

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String message = request.getMessage().toLowerCase();
        String intent = nlpService.detectIntent(message);
        String reply;

        // --- Context-aware logic ---
        if (intent.equals("greeting")) {
            reply = "Hello! I'm your health assistant. How can I help you today?";
            lastIntent = "greeting";

        } else if (intent.equals("symptom")) {
            reply = "I see — can you tell me how long you've had these symptoms and whether they're getting worse?";
            lastIntent = "symptom";

        } else if (lastIntent.equals("symptom") && message.matches(".*\\d+.*day.*")) {
            reply = "Thanks for sharing. Since it's been a few days, please rest, stay hydrated, and if your fever continues or worsens, consider seeing a doctor.";
            lastIntent = "advice";

        } else if (lastIntent.equals("symptom") && (message.contains("yesterday") || message.contains("since yesterday"))) {
            reply = "Got it. If the fever started just yesterday, monitor your temperature, drink plenty of fluids, and rest. Let me know if symptoms worsen.";
            lastIntent = "advice";

        } else if (intent.equals("advice")) {
            reply = "If your symptoms persist beyond a few days or get worse, it's best to consult a healthcare professional.";
            lastIntent = "advice";

        } else if (intent.equals("goodbye")) {
            reply = "Take care! I hope you feel better soon.";
            lastIntent = "goodbye";

        } else {
            // fallback based on context
            if (lastIntent.equals("symptom")) {
                reply = "Could you please tell me for how long you've had this symptom or if it's improving?";
            } else {
                reply = "I'm here to help. Could you tell me what symptoms you're experiencing?";
            }
        }

        return new ChatResponse(reply);
    }

    // DTO classes
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
