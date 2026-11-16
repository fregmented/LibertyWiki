// SPDX-License-Identifier: AGPL-3.0-only
package wiki.liberty.domain

/** Internal limit signal; each operation maps it to its own public result. */
internal class WikitextLimitExceeded : RuntimeException()
