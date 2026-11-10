# Pedidos por WhatsApp

Estado: implementado con la opción B y probado contra la web simulada (11 pruebas). Falta la prueba con la web real. Fecha: 2026-11-10.

Protecciones implementadas:
- El pedido solo se crea si el último mensaje del cliente es una confirmación explícita ("sí", "dale", "confirmo"). El control está en el código, no depende del modelo.
- No se crea un segundo pedido mientras haya uno pendiente de pago en la conversación.
- El envío a domicilio se rechaza antes de llamar a la web.
- Si la web rechaza el pedido, el bot recibe el motivo y deriva.
- Los avisos de estado (pago aprobado, rechazado, listo, cancelado) son textos fijos. Cada uno se envía una sola vez; si la ventana de 24 horas está cerrada, espera a que el cliente vuelva a escribir.

## Objetivo

El cliente arma y confirma el pedido por WhatsApp, sin entrar a la web. El pedido queda cargado en el sistema de la web, junto con los demás. Cuando el negocio aprueba la transferencia, el bot le avisa al cliente que el pedido está confirmado.

## Lo que ya ofrece la web

Relevado del código público de grenlus.pages.dev el 2026-11-10:

| Ruta | Uso |
|---|---|
| `POST /archivos/imagen` | Sube un diseño y devuelve su ruta |
| `POST /pedidos` | Crea un pedido de invitado, sin cuenta |
| `GET /pagos/transferencia/datos` | Datos bancarios para transferir |
| `POST /pagos/transferencia/{id}/comprobante-invitado` | Sube el comprobante, con el código de acceso del pedido |
| `GET /pedidos/invitado/{id}` | Estado del pedido, con el código de acceso |
| `PUT /pagos/transferencia/{id}/aprobar` | El negocio aprueba la transferencia desde su administración |

Estados del pedido: Recibido, En preparación, Despachado, Entregado.

Formas de entrega: retiro en el local, envío a domicilio o a coordinar. La web obliga a pagar con Mercado Pago cuando hay envío a domicilio, así que **por transferencia solo se puede retirar o coordinar**.

## Flujo propuesto

1. El bot arma el pedido: producto, cantidades por talle y color, diseño con posición y tamaño, nombre y forma de entrega.
2. Muestra un resumen con el total de `calcular_presupuesto` y pide confirmación explícita.
3. Con el "sí" del cliente, sube los diseños y crea el pedido en la web como invitado, con pago por transferencia.
4. Responde: "Recibimos tu pedido #N", más los datos para transferir.
5. Cuando el cliente manda la foto del comprobante, el bot la sube al pedido.
6. El negocio revisa diseño y comprobante en la administración de la web y aprueba la transferencia.
7. El chatbot consulta el estado cada pocos minutos. Al ver la aprobación, envía "¡Tu pedido está confirmado!", y más adelante avisa cuando pasa a preparación o despacho.

## Decisiones

| Tema | Decisión |
|---|---|
| Fuente de verdad | El pedido vive en la web. El chatbot guarda solo el número, el código de acceso cifrado y el último estado visto. |
| Doble creación | Una sola creación por oportunidad: si hay un pedido creado, no se crea otro. |
| Confirmación del cliente | Solo con un "sí" explícito al resumen, nunca por suposición del modelo. |
| Aviso pasadas 24 h | WhatsApp no permite texto libre: hace falta una plantilla de Meta aprobada, por ejemplo `pedido_confirmado`. |
| Cartelería y pedidos especiales | Siguen derivándose a una persona. |

## Lo que muestra el código del backend

Revisado en github.com/lucasmilharcic/Grenlus, commit `8a9b379` del 2026-10-20:

- **Crear pedidos exige una cuenta.** `POST /pedidos` requiere usuario autenticado, y el servicio rechaza a los invitados con "Iniciá sesión para registrar tu pedido". La consulta y el comprobante de invitado existen, pero hoy no se pueden crear pedidos de invitado.
- **Precios:** el backend calcula el total con sus propios datos, así que el pedido no depende del presupuesto del bot.
- **Datos del pedido:** nombre, teléfono, email, forma de entrega (`RETIRO`, `ENVIO_DOMICILIO`, `COORDINAR`), forma de pago (`MERCADO_PAGO`, `TRANSFERENCIA`) y detalles con producto, cantidad, talle, color y diseños.
- **Diseño:** ruta de imagen subida con `POST /archivos/imagen` (pública, solo imágenes, hasta 15 MB), posición (`FRENTE`, `ESPALDA`, `MANGA_DERECHA`, `MANGA_IZQUIERDA`, `PECHO`, `OTRA`), tamaño (`CHICA`, `MEDIA`, `GRANDE`) y observaciones.
- **Estados:** pedido `PENDIENTE`, `PAGADO`, `EN_PREPARACION`, `LISTO`, `ENTREGADO`, `CANCELADO`; pago `PENDIENTE`, `APROBADO`, `RECHAZADO`, `CANCELADO`.
- **"Pedido confirmado"** = pago `APROBADO`, que es lo que hace la aprobación de transferencia desde la administración.

## Cómo puede crear pedidos el chatbot

| Opción | Cambios en la web | A favor | En contra |
|---|---|---|---|
| **A. Endpoint de integración** (recomendada) | Un endpoint nuevo protegido con una clave compartida, que crea el pedido como invitado, lo marca con origen WhatsApp y devuelve su código de acceso | Cada pedido tiene su propio código; queda claro qué pedidos vinieron por WhatsApp; no hay contraseñas de usuarios en el chatbot | Hay que modificar y desplegar el backend de la web |
| **B. Cuenta de servicio** | Ninguno | Se puede hacer ya | Todos los pedidos de WhatsApp quedan bajo un mismo usuario de la web; el chatbot guarda la contraseña de esa cuenta y renueva su sesión |

Con la opción A, el chatbot consulta el estado con `GET /pedidos/invitado/{id}` y sube comprobantes con `comprobante-invitado`, que ya existen.

**Decisión (2026-11-10): opción B**, porque es una prueba y no se quiere modificar la web. Se revisa si el proyecto pasa a producción.

Cómo funciona con la cuenta de servicio:
- El chatbot inicia sesión con `POST /auth/login` y recibe un token que dura 1 hora. Si vence, vuelve a iniciar sesión.
- Crea el pedido con `POST /pedidos`, con el nombre y teléfono del cliente real en los datos del pedido.
- Sube el comprobante con `comprobante-cuenta` y consulta los estados con `GET /pedidos/mis-compras`.
- El usuario y la contraseña van en `.env` (`GRENLUS_BOT_USERNAME` y `GRENLUS_BOT_PASSWORD`), nunca en el código.
