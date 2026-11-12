package android.security

/**
 * The `android.security.KeyStoreException` keystore2 puts under the `UnrecoverableKeyException` of `KeyStore.getEntry`
 * and the `InvalidKeyException` of `Cipher.init` (API 33+ reads it). Its constructor is package-private, hence the
 * package; the stub jar's getters are overridden with the values a device would report.
 */
class Keystore2Failure(private val code: Int, private val transient: Boolean) : KeyStoreException() {
    override fun getNumericErrorCode(): Int = code

    override fun isTransientFailure(): Boolean = transient

    override val message: String get() = "keystore2 error $code (transient=$transient)"
}
