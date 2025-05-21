// Importaciones necesarias de Ktor para construir el servidor
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.request.*
import io.ktor.routing.*
import io.ktor.features.*
import io.ktor.serialization.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
// Importaciones estándar de Java y Kotlin
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.io.File

// Importación del cliente MQTT de Eclipse Paho
import org.eclipse.paho.client.mqttv3.*

// Data class que representa una muestra de consumo energético con timestamp
data class EnergyData(
    val timestamp: String,
    val consumption: Double,
)

// Data class para recibir alertas desde el cliente
data class Alert(
    val message: String,
)

// URL de conexión a la base de datos SQLite
val dbUrl = "jdbc:sqlite:energy_monitor.db"

/**
 * Inicializa la base de datos creando la tabla `energy_data`
 * si no existe. Esta tabla almacena registros de consumo con
 * un timestamp y un valor de consumo.
 */
fun initDatabase() {
    DriverManager.getConnection(dbUrl).use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS energy_data (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp TEXT NOT NULL,
                    consumption REAL NOT NULL
                )
                """.trimIndent(),
            )
        }
    }
}

/**
 * Guarda un nuevo registro de consumo energético en la base de datos.
 * @param timestamp Fecha y hora en formato ISO 8601.
 * @param consumption Valor de consumo energético en kW.
 */
fun saveEnergyData(
    timestamp: String,
    consumption: Double,
) {
    DriverManager.getConnection(dbUrl).use { conn ->
        val pstmt = conn.prepareStatement("INSERT INTO energy_data (timestamp, consumption) VALUES (?, ?)")
        pstmt.setString(1, timestamp)
        pstmt.setDouble(2, consumption)
        pstmt.executeUpdate()
    }
}

/**
 * Configura y arranca un cliente MQTT que se conecta al broker
 * público de HiveMQ, se suscribe al tópico "energy/consumption"
 * y guarda cada valor recibido en la base de datos con un timestamp.
 */
fun startMqttClient() {
    val broker = "tcp://broker.hivemq.com:1883" // Broker público
    val topic = "energy/consumption" // Tópico de consumo
    val client = MqttClient(broker, MqttClient.generateClientId())

    client.connect() // Conexión al broker
    client.subscribe(topic) // Suscripción al tópico

    // Definición del callback que maneja eventos del cliente MQTT
    client.setCallback(
        object : MqttCallback {
            // Si se pierde la conexión
            override fun connectionLost(cause: Throwable?) {
                println("MQTT Connection lost: $cause")
            }

            // Cuando llega un nuevo mensaje del tópico suscrito
            override fun messageArrived(
                topic: String?,
                message: MqttMessage?,
            ) {
                val payload = message?.toString()?.toDoubleOrNull() // Convertir el mensaje a Double
                if (payload != null) {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)
                    saveEnergyData(timestamp, payload) // Guardar dato en la base de datos
                    println("Dato recibido: $payload kW en $timestamp")
                }
            }

            // Cuando un mensaje ha sido entregado completamente (no se usa aquí)
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        },
    )
}

/**
 * Función principal: inicializa la base de datos, arranca el cliente MQTT
 * y lanza el servidor HTTP en el puerto 8080.
 */
fun main() {
    initDatabase() // Crear tabla si no existe
    startMqttClient() // Comenzar a escuchar MQTT

    // Configurar y arrancar el servidor Ktor
    embeddedServer(Netty, port = 8080) {
        // Configura serialización JSON usando Gson
        install(ContentNegotiation) {
            gson()
        }

        // Permite peticiones desde cualquier origen (útil para desarrollo)
        install(CORS) {
            anyHost()
        }

        // Definición de rutas del servidor
        routing {
            // Ruta para servir archivos estáticos (ej. HTML, CSS, JS)
            static("/") {
                staticRootFolder = File("src/main/resources/static") // Carpeta raíz
                default("index.html") // Archivo predeterminado
            }

            // Ruta GET para obtener los últimos 100 registros de consumo
            get("/api/energy") {
                val data = mutableListOf<List<Any>>()
                DriverManager.getConnection(dbUrl).use { conn ->
                    val stmt = conn.createStatement()
                    val rs = stmt.executeQuery("SELECT timestamp, consumption FROM energy_data ORDER BY timestamp DESC LIMIT 100")
                    while (rs.next()) {
                        // Agrega cada fila como una lista [timestamp, consumo]
                        data.add(listOf(rs.getString("timestamp"), rs.getDouble("consumption")))
                    }
                }
                call.respond(data) // Devuelve los datos en formato JSON
            }

            // Ruta POST para recibir alertas desde el cliente
            post("/api/alert") {
                val alert = call.receive<Alert>() // Deserializa el cuerpo del mensaje a objeto Alert
                println("Alerta enviada: ${alert.message}")
                call.respond(mapOf("status" to "success", "message" to "Alerta enviada"))
            }
        }
    }.start(wait = true)
}
