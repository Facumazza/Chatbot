# Chatbot de WhatsApp con IA

Asistente virtual para empresas: atiende por WhatsApp, cotiza con datos reales y deriva a operadores.

El diseño está en [docs/](docs/).

## Requisitos

- JDK 21
- Docker Desktop

## Ejecutar en local

1. Copiá `.env.example` como `.env` y definí una clave.
2. Levantá la base:

```bash
docker compose up -d
```

3. Desde `backend/`, iniciá la aplicación:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

- Estado: http://localhost:8080/actuator/health
- API (solo perfil `dev`): http://localhost:8080/swagger-ui.html

## Tests

Requieren Docker en ejecución. Desde `backend/`:

```bash
./mvnw verify
```
