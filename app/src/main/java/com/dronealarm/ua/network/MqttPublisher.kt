package com.dronealarm.ua.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MqttPublisher(
    private val brokerUri: String = "tcp://test.mosquitto.org:1883",
    private val topic: String = "airalert/civilian/dronealarm"
) {
    private val clientId = "drone-alarm-${System.currentTimeMillis()}"
    private val client = MqttClient(brokerUri, clientId, MemoryPersistence())

    suspend fun publish(payload: String, enabled: Boolean) {
        if (!enabled) return
        withContext(Dispatchers.IO) {
            if (!client.isConnected) {
                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 5
                }
                client.connect(options)
            }
            client.publish(topic, MqttMessage(payload.toByteArray()).apply { qos = 1 })
        }
    }

    fun close() {
        if (client.isConnected) client.disconnect()
        client.close()
    }
}
