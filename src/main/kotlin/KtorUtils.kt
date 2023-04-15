import io.ktor.util.*

inline fun <T : Any> getAttributeKeyFor(name: String): AttributeKey<T>
{
    return AttributeKey(name)
}