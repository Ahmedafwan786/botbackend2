# Health Chatbot Backend (Ready-to-run)

This Spring Boot project provides a `/chat` REST endpoint that uses Apache OpenNLP for intent classification.  
If a trained model is not found on startup, the application trains a new model using the bundled `training.txt` dataset and persists it to the filesystem for future runs.

## Requirements
- Java 17+
- Maven 3.6+

## Run
1. Unzip the project (if zipped).
2. From the project root, run:
```
mvn clean package
mvn spring-boot:run
```
3. POST JSON to `http://localhost:8080/chat`:
```
POST /chat
Content-Type: application/json

{ "message": "I have a fever and headache" }
```
Response:
```
{ "reply": "response text" }
```

The model is trained from `src/main/resources/training.txt` if no model binary exists.
