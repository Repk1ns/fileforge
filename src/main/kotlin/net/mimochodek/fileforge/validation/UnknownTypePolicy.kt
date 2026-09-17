package net.mimochodek.fileforge.validation

/**
 * What to do with a content type that has no known magic-byte signature, and so
 * cannot be verified against the content.
 */
public enum class UnknownTypePolicy {

    /**
     * Accept it - the MIME whitelist has already been enforced, the content just
     * cannot be cross-checked. This is the default.
     */
    ALLOW,

    /** Reject it, so only content the library can actually verify is stored. */
    REJECT,
}
