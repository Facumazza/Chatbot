# Etapa 1: Análisis de requerimientos

Estado: borrador para validar. Fecha: 2026-11-10.

## Qué define esta etapa y por qué

Esta etapa fija qué hace el sistema, qué queda fuera de la primera versión y qué restricciones externas lo condicionan. No incluye código.

Se relaciona con el resto así: los requerimientos alimentan la arquitectura (Etapa 2), las entidades alimentan el modelo de datos (Etapa 3) y los criterios de aceptación se convierten en tests (Etapa 9).

## 1. Actores

| Actor | Qué hace |
|---|---|
| Cliente final | Escribe por WhatsApp. No tiene cuenta ni instala nada. |
| Asistente IA | Interpreta, consulta datos reales, responde o deriva. |
| Operador | Toma conversaciones derivadas y responde desde el panel. |
| Administrador | Configura el bot, FAQs y catálogo. Ve estadísticas. |
| Empresa (tenant) | Dueña de los datos, el número de WhatsApp y la configuración. |
| Meta / OpenAI | Servicios externos de mensajería y de modelo de lenguaje. |

## 2. Requerimientos funcionales

Prioridad: **M** = entra en el MVP, **P** = posterior.

### Conversaciones

| ID | Requerimiento | Prioridad |
|---|---|---|
| RF-01 | Identificar al cliente dentro de cada empresa por su identificador de WhatsApp (BSUID) y, cuando Meta lo informe, por su número. El número puede faltar si el cliente usa nombre de usuario. | M |
| RF-02 | Crear una conversación con el primer mensaje, o si la anterior está cerrada. | M |
| RF-03 | Persistir todos los mensajes entrantes y salientes, con su estado de entrega. | M |
| RF-04 | Mantener el contexto: historial reciente más datos conocidos del cliente. | M |
| RF-05 | Procesar conversaciones en paralelo, y en orden dentro de cada una. | M |
| RF-06 | Estados de conversación: `ACTIVE`, `WAITING_FOR_AGENT`, `WITH_AGENT`, `CLOSED`. | M |
| RF-07 | Cerrar por inactividad, con plazo configurable. | M |
| RF-08 | Agrupar ráfagas de mensajes seguidos y contestar una sola vez. | M |
| RF-09 | Recibir imágenes y documentos, guardarlos, asociarlos al lead y confirmar la recepción. Los audios se guardan y se pide escribir la consulta. | M |
| RF-10 | Transcribir audios e interpretar imágenes con IA. | P |

### Inteligencia artificial

| ID | Requerimiento | Prioridad |
|---|---|---|
| RF-11 | Interpretar mensajes y generar respuestas con un LLM. | M |
| RF-12 | Prompt de sistema editable (personalidad y reglas) y versionado. | M |
| RF-13 | Responder datos del negocio (precios, stock, horarios) solo a partir de herramientas que consultan la base. | M |
| RF-14 | Si no hay dato, decirlo y ofrecer un operador. Nunca estimar ni inventar. | M |
| RF-15 | Pedir aclaración ante mensajes ambiguos. | M |
| RF-16 | Rechazar temas ajenos al negocio. Ver restricción R-01. | M |
| RF-17 | Tratar el texto del cliente como dato no confiable (defensa contra prompt injection). | M |
| RF-18 | Búsqueda semántica sobre documentos del negocio (RAG). | P |

### Clientes y oportunidades

| ID | Requerimiento | Prioridad |
|---|---|---|
| RF-19 | Registrar nombre, teléfono y datos relevantes. | M |
| RF-20 | Distinguir clientes nuevos y recurrentes. | M |
| RF-21 | Registrar intereses y necesidades detectados en la charla. | M |
| RF-22 | Crear y actualizar leads con estado y producto de interés. | M |
| RF-23 | Registrar y respetar pedidos de baja. Ver R-05. | M |

### Derivación humana

| ID | Requerimiento | Prioridad |
|---|---|---|
| RF-24 | Derivar por pedido explícito, por decisión del modelo o por reglas configurables. | M |
| RF-25 | Asignar o tomar una conversación desde el panel. | M |
| RF-26 | Bloquear a la IA mientras un operador tenga el control. La verificación se hace en el backend. | M |
| RF-27 | Permitir responder al operador desde el panel. | M |
| RF-28 | Registrar quién tomó y quién devolvió cada conversación, y cuándo. | M |
| RF-29 | Fuera del horario de atención, avisar al cliente y dejar la conversación en espera. | M |
| RF-30 | Si la ventana de 24 h venció, exigir una plantilla para volver a escribirle. | M |

### Panel administrativo

| ID | Requerimiento | Prioridad |
|---|---|---|
| RF-31 | Listar, filtrar y leer conversaciones. | M |
| RF-32 | Consultar clientes y leads. | M |
| RF-33 | Editar instrucciones, FAQs y catálogo sin desplegar. | M |
| RF-34 | Ver estadísticas básicas: volumen, derivaciones, leads, costo de IA. | M |
| RF-35 | Activar y desactivar el asistente. Los mensajes se siguen guardando. | M |
| RF-36 | Login con roles `ADMIN` y `AGENT`. | M |
| RF-37 | Alta autoservicio de empresas y facturación. | P |

## 3. Restricciones externas (verificadas el 2026-11-10)

Estas reglas salen de la documentación oficial de Meta y afectan el diseño.

| ID | Regla | Impacto en el sistema |
|---|---|---|
| R-01 | Los términos (sección 4.7, actualizados el 23/09/2026) prohíben a proveedores de IA ofrecer su tecnología por la plataforma cuando sea la funcionalidad principal y no "incidental o auxiliar". Meta decide en cada caso. | El bot debe atender solo temas del negocio (RF-16). Ver riesgo 1 para la venta como producto. |
| R-02 | Esos términos impiden a un proveedor de IA usar datos de la plataforma para entrenar modelos. | No usar conversaciones para entrenar ni hacer fine-tuning sin revisión legal. |
| R-03 | Solo hay texto libre dentro de las 24 h desde el último mensaje del cliente. Fuera de esa ventana, solo plantillas aprobadas. | Calcular la ventana por conversación (RF-30). |
| R-04 | La política exige, para automatización dentro de la ventana, vías de escalamiento rápidas, claras y directas. | La derivación es un requisito de cumplimiento, no solo una función. |
| R-05 | Hay que contar con opt-in para contactar y respetar todo pedido de baja, dentro o fuera de WhatsApp. | Guardar consentimiento y bloquear envíos a quien se dio de baja. |
| R-06 | Desde el 01/10/2026, cada número tiene 1.000 mensajes de servicio gratis por mes. Después se cobran, y las plantillas utility también se cobran dentro de la ventana. | Cada respuesta del bot consume cupo: una sola respuesta por turno (RF-08), medir consumo y exigir medio de pago. |
| R-07 | El límite inicial es de 250 usuarios únicos cada 24 h fuera de ventana, por portfolio y compartido entre números. | Afecta campañas y recontacto de leads, no la atención entrante. |

Orden de magnitud de R-06: con 10 respuestas por conversación, el cupo gratis alcanza para unas 100 conversaciones por mes y por número.

## 4. Requerimientos no funcionales

| ID | Requerimiento | Criterio |
|---|---|---|
| RNF-01 | Webhook rápido: confirmar a Meta sin esperar a la IA. | Guardar y responder 200 en menos de 1 s. |
| RNF-02 | Idempotencia: Meta puede reenviar eventos. | Restricción única sobre el `wamid`. |
| RNF-03 | Autenticidad del webhook. | Validar la firma `X-Hub-Signature-256` sobre el cuerpo crudo. |
| RNF-04 | Resiliencia ante caídas de OpenAI o Meta. | Reintentos con backoff, timeouts y mensaje de contingencia. |
| RNF-05 | Secretos fuera del código. | Variables de entorno; nada en el repositorio. |
| RNF-06 | Datos personales. | Teléfonos enmascarados en logs, retención configurable, borrado por cliente. |
| RNF-07 | Aislamiento entre empresas. | Toda tabla de negocio lleva `tenant_id`, con test específico. |
| RNF-08 | Observabilidad. | Logs estructurados con id de correlación; tokens y costo por conversación. |
| RNF-09 | Escalado horizontal. | Instancias sin estado; varias pueden correr a la vez sin duplicar respuestas. |

## 5. Huecos del pedido original, ya incorporados

- **Falta una tabla de catálogo.** El bot debe informar productos con datos reales, pero la lista de tablas no la incluía. Se agregan `products` y `services`, o una tabla única.
- **Faltan entidades de soporte.** Se agregan `tenants`, historial de derivaciones, consentimientos, versiones del prompt, eventos de webhook y uso de IA.
- **Login del panel.** Se propone que `human_agents` cubra también a los administradores, mediante un rol.
- **Dónde responde el operador.** Desde el panel, para que el backend sepa siempre quién tiene el control.
- **Bot desactivado.** Se guardan los mensajes y se envía un aviso configurable.
- **Identificación como IA.** El bot se presenta como asistente virtual desde el primer mensaje.

## 6. Criterios de aceptación del MVP

1. Un mensaje real de WhatsApp recibe una respuesta generada con contexto y queda guardado en PostgreSQL.
2. Si Meta reenvía el mismo evento, no se procesa ni se responde dos veces.
3. Una petición con firma inválida se rechaza y no genera ningún registro de negocio.
4. Una pregunta de precio devuelve el valor de la base. Si cambia en el panel, la siguiente respuesta usa el nuevo valor sin desplegar.
5. Una pregunta sobre un producto inexistente no produce datos inventados.
6. Mientras un operador tiene el control, la IA no envía nada, incluso con mensajes ya en cola.
7. Al devolver la conversación, la IA retoma con el historial y queda registrado el evento.
8. Tras 24 h sin mensajes del cliente, el sistema impide el texto libre y pide una plantilla.
9. Quien pide la baja no recibe más mensajes iniciados por la empresa.
10. Los datos de una empresa no son accesibles desde otra.

## 7. Riesgos principales

| # | Riesgo | Mitigación |
|---|---|---|
| 1 | Meta podría considerar proveedor de IA a un SaaS cuyo producto central sea un bot. El texto no exime expresamente a la atención al cliente. | Posicionarlo como automatización de atención, limitar al dominio de cada empresa y hacer revisión legal antes de vender. |
| 2 | Alucinaciones sobre precios o condiciones. | Datos solo por herramientas (RF-13), casos de evaluación en Etapa 9. |
| 3 | Costos variables de OpenAI y Meta. | Limitar historial y tokens, medir por empresa. |
| 4 | Vender a varias empresas requiere el camino de Tech Provider y Embedded Signup. | Credenciales de WhatsApp por tenant desde el inicio; el onboarding queda para después del MVP. |
| 5 | Transferencia internacional de datos: el art. 12 de la Ley 25.326 la restringe hacia países sin protección adecuada, salvo consentimiento u otras excepciones. No encontré una decisión firme que reconozca a Estados Unidos como adecuado. | Aviso y registro de consentimiento en el primer contacto, minimizar lo enviado al modelo y validarlo con un abogado antes de producción. |
| 6 | Diseños con marcas, escudos o personajes protegidos. | El bot no los aprueba: deriva a un operador. |

## 8. Decisiones abiertas

| # | Decisión | Recomendación |
|---|---|---|
| D-1 | Multiempresa | `tenant_id` en el modelo desde el día uno, con una sola empresa piloto y sin onboarding autoservicio. |
| D-2 | Panel | React + Vite como aplicación separada que consume la API. |
| D-3 | País e idioma inicial | **Resuelta:** Argentina, español rioplatense, precios en ARS. |
| D-4 | Empresa piloto | **Resuelta:** Grenlus, indumentaria, artículos y cartelería personalizados. Ver sección 9. |
| D-5 | Cuentas de Meta y OpenAI | Pendiente: se crean antes de las Etapas 5 y 6. |
| D-6 | Origen del catálogo | **Resuelta:** sincronización en modo lectura desde la API de la web. |
| D-7 | Número del bot | Pendiente: definir si usa el número actual de WhatsApp de Grenlus u otro. |

## 9. Requerimientos del piloto: Grenlus

Referencia: https://grenlus.pages.dev/ (revisada el 2026-11-10). Vende indumentaria (10 productos), artículos como tazas, botellas y chopps (3) y cartelería a medida (sin productos cargados). La web ya tiene personalizador visual, carrito, login y "Mis compras".

El cliente manda su diseño como imagen, así que recibir imágenes entra en el MVP (RF-09).

**Regla de precio observada en la web:**

`precio unitario = precio base + suma de estampas por posición según tamaño + adicional de talle especial`

- Estampas: Chica (hasta 15 × 15 cm), Mediana (hasta 25 × 25 cm) y Grande (más de 25 × 25 cm), con valores y medidas propios por producto.
- Posiciones: frente, espalda y mangas, habilitadas según el producto.
- Descuento: desde 6 unidades, 10 % sobre el precio base.
- Ejemplo: remera $14.900 + estampa chica $2.000 = $16.900.

| ID | Requerimiento | Prioridad |
|---|---|---|
| RP-01 | Catálogo con productos, talles, colores, posiciones y tamaños de estampa. Solo se ofrecen productos activos. | M |
| RP-02 | Precio unitario en ARS según la regla observada, con vigencia. | M |
| RP-03 | Presupuesto calculado por el backend, nunca por el modelo. | M |
| RP-04 | El bot reúne producto, cantidad, talles, colores, diseños con posición y tamaño, y localidad, y los guarda en el lead. | M |
| RP-05 | Informar plazos, mínimos, envíos y medios de pago solo desde datos configurados. | M |
| RP-06 | Al recibir un diseño, el bot confirma la recepción y un operador valida la factibilidad. | M |
| RP-07 | El bot no aprueba diseños con marcas o personajes protegidos ni acepta pedidos especiales fuera del catálogo. | M |
| RP-08 | El bot envía el enlace del producto para personalizarlo y comprarlo en la web. Los pedidos grandes o especiales pasan a un operador. | M |
| RP-09 | Cartelería: el bot reúne medidas, tipo y ubicación, y deriva a un operador para cotizar. | M |
| RP-10 | Consulta del estado de "Mis compras" desde WhatsApp. | P |
