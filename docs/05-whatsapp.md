# Etapa 5: Integración con WhatsApp Cloud API

Estado: implementada y probada contra un servidor que simula a Meta. Falta la prueba con una cuenta real.

## Qué quedó construido

| Pieza | Qué hace |
|---|---|
| `GET /webhooks/whatsapp` | Responde la verificación de Meta |
| `POST /webhooks/whatsapp` | Valida la firma y guarda el evento crudo |
| `WebhookEventProcessor` | Registra cliente, conversación y mensaje, y deja un turno pendiente |
| `OutboundDispatcher` | Envía los mensajes pendientes y registra el resultado |
| `MediaDownloadWorker` | Descarga y guarda imágenes, PDF y audios |
| `DevEchoWorker` | Respuesta fija temporal, hasta la Etapa 6 |

Versión de la API: `v27.0`, la que usa la documentación de Meta al 2026-11-10. Se cambia con `WHATSAPP_API_VERSION`.

## Decisiones

| Situación | Comportamiento |
|---|---|
| Meta reenvía un evento | Se detecta por el id del mensaje y no se duplica |
| Un error temporal de Meta | Hasta 5 intentos con espera creciente |
| Un rechazo definitivo | El mensaje queda `FAILED` con su código |
| Se corta la conexión durante el envío | Queda `UNKNOWN` y no se reintenta, para no repetirle el mensaje al cliente |
| Pasaron 24 horas sin mensajes del cliente | No se permite texto libre |
| Estados que llegan desordenados | Solo avanzan: un `delivered` tardío no pisa un `read` |
| Archivo no permitido o mayor a 25 MB | Queda `REJECTED`; el mensaje se conserva |
| Destinatario sin teléfono conocido | Se envía con su identificador de WhatsApp (BSUID) |

## Prueba con una cuenta real

Los nombres de los menús de Meta pueden variar.

### 1. Crear la app

1. Entrá a https://developers.facebook.com y registrate como desarrollador.
2. Creá una app de tipo **Business** y agregale el producto **WhatsApp**.
3. En **WhatsApp > API Setup** vas a ver un número de prueba gratuito.
4. Agregá tu celular como destinatario permitido.

### 2. Completar `.env`

| Variable | Dónde está |
|---|---|
| `WHATSAPP_PHONE_NUMBER_ID` | API Setup, "Phone number ID" |
| `WHATSAPP_BUSINESS_ACCOUNT_ID` | API Setup, "WhatsApp Business Account ID" |
| `WHATSAPP_DISPLAY_PHONE_NUMBER` | API Setup, número de prueba |
| `WHATSAPP_ACCESS_TOKEN` | API Setup, token temporal (vence en horas) |
| `WHATSAPP_APP_SECRET` | App Settings > Basic, "App Secret" |
| `WHATSAPP_VERIFY_TOKEN` | Ya generado en tu `.env` |

### 3. Exponer el backend

Meta exige una URL HTTPS pública. Con la aplicación corriendo:

```bash
docker run --rm cloudflare/cloudflared:latest tunnel --url http://host.docker.internal:8080
```

Copiá la URL `https://....trycloudflare.com` que aparece. Cambia cada vez que se inicia.

### 4. Configurar el webhook

En **WhatsApp > Configuration**:

1. Callback URL: `https://....trycloudflare.com/webhooks/whatsapp`
2. Verify token: el valor de `WHATSAPP_VERIFY_TOKEN`
3. Suscribí el campo **messages**.

### 5. Probar

Con `DEV_ECHO_ENABLED=true`, escribile al número de prueba desde tu celular. A los pocos segundos debería llegarte la respuesta fija.

### Si los mensajes no llegan al backend

La app tiene que estar suscripta a la cuenta de WhatsApp. En el Graph API Explorer, `POST <WHATSAPP_BUSINESS_ACCOUNT_ID>/subscribed_apps` debe responder `{"success": true}`.

## Comprobado en la prueba real (2026-11-10)

- **Celulares argentinos:** Meta los informa como `549...`, pero rechaza enviarles con ese 9 (error 131030). Sin el 9 los acepta y los asocia al mismo número. El envío quita el 9; la base guarda el valor original.
- **Envío con BSUID:** el número de prueba lo rechaza con 131030, porque su lista de destinatarios solo admite teléfonos. Falta comprobarlo con un número de producción.

## Pendiente de otras etapas

- Plantillas para escribir fuera de la ventana de 24 horas.
- Evento `system` de cambio de número del usuario.
- Token permanente de usuario de sistema para producción.
