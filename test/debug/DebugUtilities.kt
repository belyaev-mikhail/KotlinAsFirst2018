package debug

import com.fasterxml.jackson.core.*
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.deser.ContextualDeserializer
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.type.TypeFactory
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.intellij.lang.annotations.Language
import kotlin.reflect.KClass

private fun TreeNode.traverseToNext(codec: ObjectCodec): JsonParser = traverse(codec).apply { nextToken() }

private object MapSerializer : JsonSerializer<Map<*, *>>() {
    override fun serialize(value: Map<*, *>?,
                           gen: JsonGenerator,
                           serializers: SerializerProvider) {
        if (value == null) {
            gen.writeNull()
            return
        }

        gen.writeStartArray()
        for ((k, v) in value) {
            gen.writeStartObject()
            gen.writeFieldName("key")
            serializers.defaultSerializeValue(k, gen)
            gen.writeFieldName("value")
            serializers.defaultSerializeValue(v, gen)
            gen.writeEndObject()
        }
        gen.writeEndArray()
    }
}
class MapDeserializer : StdDeserializer<Map<*, *>>(Map::class.java), ContextualDeserializer {
    override fun createContextual(ctxt: DeserializationContext, property: BeanProperty?): JsonDeserializer<*> {
        val type = ctxt.contextualType ?: property?.type ?: return this
        return Sub(type)
    }

    fun deserialize(p: JsonParser, ctxt: DeserializationContext, keyType: JavaType, valueType: JavaType): Map<*, *> {
        val keyDs = ctxt.findRootValueDeserializer(keyType)
        val valueDs = ctxt.findRootValueDeserializer(valueType)

        val res = mutableMapOf<Any?, Any?>()

        val tree: TreeNode = p.codec.readTree(p)

        when (tree) {
            is ArrayNode -> {
                for (entry in tree) {
                    val obj = entry as ObjectNode
                    val key = keyDs.deserialize(obj["key"].traverseToNext(p.codec), ctxt)
                    val value = valueDs.deserialize(obj["value"].traverseToNext(p.codec), ctxt)

                    res[key] = value
                }
            }
            is ObjectNode -> {
                for ((key, value) in tree.fields()) {
                    res[key] = valueDs.deserialize(value.traverseToNext(p.codec), ctxt)
                }
            }
        }

        return res
    }

    inner class Sub(val contextualType: JavaType) : JsonDeserializer<Map<*, *>>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Map<*, *> =
                deserialize(
                        p,
                        ctxt,
                        contextualType.containedTypeOrUnknown(0),
                        contextualType.containedTypeOrUnknown(1)
                )
    }

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Map<*, *> =
            deserialize(p, ctxt, TypeFactory.unknownType(), TypeFactory.unknownType())
}
private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule())
        .registerModule(Jdk8Module())
        .registerModule(
                SimpleModule()
                        .addSerializer(Map::class.java, MapSerializer)
                        .addDeserializer(Map::class.java, MapDeserializer())
        )

@PublishedApi
internal fun <T> parseTestJson(@Language("JSON") json: String, tref: TypeReference<T>): T =
        objectMapper.readValue<T>(json, tref)
inline fun <reified T> parseTestJson(@Language("JSON") json: String): T =
        parseTestJson(json, object : TypeReference<T>(){})