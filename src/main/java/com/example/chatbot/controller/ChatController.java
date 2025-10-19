package com.example.chatbot.controller;

import com.example.chatbot.service.NLPService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat")
public class ChatController {

    @Autowired
    private NLPService nlpService;

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String reply = nlpService.generateResponse(request.getMessage());
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
