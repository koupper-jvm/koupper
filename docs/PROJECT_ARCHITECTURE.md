# Arquitectura de Proyectos Koupper

Koupper es un framework diseñado para escalar desde simples scripts hasta aplicaciones empresariales desplegables en la nube (arquitectura Serverless / Lambda-lith).

Este documento explica la diferencia fundamental entre el uso de **Scripts Independientes (Standalone Scripts)** y **Proyectos Estructurados**, y cómo utilizar las herramientas de *Scaffolding* (`koupper new`) para crear la infraestructura adecuada.

---

## 1. Scripts Independientes vs Proyectos

Koupper permite ejecutar código de dos maneras principales:

### A. Scripts Independientes (`.kts`)
Ideal para tareas automatizadas, CI/CD, mantenimiento o prototipado rápido.
- **Estructura:** Un solo archivo `.kts` (ej. `script.kts`).
- **Ejecución:** `koupper run script.kts`
- **Propósito:** Tareas efímeras o de fondo.
- **Inyección de Dependencias:** Limitada al motor interno de Koupper (Octopus) y los *Service Providers* básicos.

### B. Proyectos Estructurados (Scaffolding con `koupper new`)
Ideal para APIs completas, aplicaciones web, despliegues en la nube (AWS Lambda / EC2) o flujos complejos de datos.
- **Estructura:** Un proyecto Gradle completo con directorios, dependencias y empaquetado.
- **Ejecución:** `java -jar build/libs/app.jar` o despliegue Serverless.
- **Propósito:** Código de producción, tests unitarios, APIs y despliegues robustos.
- **Inyección de Dependencias:** Total control a través de inyección de dependencias estricta, enrutadores y controladores estructurados.

---

## 2. Creando Proyectos con `koupper new`

Koupper provee una CLI integrada (Octopus Engine) para generar proyectos base (*scaffolding*). Dependiendo de tus necesidades, puedes elegir distintos **Templates**.

### Template: `model-project` (Scripts/Jobs)
Este es el template por defecto si deseas crear un entorno estructurado para scripts complejos o tareas programadas.

- **Comando:** `koupper new <nombre> --template=model-project --packageName=com.mi.paquete`
- **Qué hace:** Crea un proyecto Gradle base con las dependencias de Koupper, **limpia** la estructura de APIs web (eliminando carpetas como `server` o `http`), y genera un archivo `Bootstrapping.kt` en el paquete indicado.
- **Casos de uso:** 
  - Pipelines de datos.
  - Ejecución orquestada de scripts.
  - Workers en background.

### Template: `http` (Web / Serverless / Lambda-lith)
Si deseas construir una API completa (RESTful) que pueda correr tanto en un contenedor tradicional como en AWS Lambda (Dual-Deployment).

- **Comando:** `koupper new <nombre> --template=http --packageName=com.mi.paquete`
- **Qué hace:** Clona el proyecto modelo completo **sin eliminar nada**. Conserva la estructura predeterminada:
  - `server/Setup.kt`: Configuración del servidor Grizzly.
  - `http/controllers/`: Controladores de enrutamiento web (`@WebRoute`).
  - `io/mp/`: Paquete base interno.
- **Casos de uso:**
  - Microservicios RESTful.
  - Aplicaciones Lambda-lith (Serverless).
  - WebSockets y Server-Sent Events (SSE).

> **Nota Técnica:** En el modo HTTP, Koupper configura internamente el archivo `server/Setup.kt` (ajustando el puerto especificado en la configuración) y deja intactos los controladores de ejemplo, brindando un punto de partida funcional out-of-the-box.

---

## 3. Empaquetado y Distribución

Cualquier proyecto generado con Koupper incluye configuraciones avanzadas de Gradle (Shadow JAR) que permiten compilar un ejecutable completo ("Fat JAR").

```bash
# Compilar proyecto completo
.\gradlew build
# Ejecutar
java -jar build/libs/<nombre>-all.jar
```

Para entornos Serverless, Koupper genera en modo `http` los *entrypoints* necesarios (como `LambdaEntryPoint.kt`) que permiten a proveedores como AWS API Gateway delegar el enrutamiento HTTP interno directamente al contenedor sin requerir un servidor web embutido pesado.
