package com.ruhui.hoyocookiemanager

/**
 * Skland owns the CSV schema boundary. The shared template handles cloud upload
 * and orchestration; no JSON or token-file account fallback exists.
 */
object SklandCloudConfigAdapter {
    const val ACCOUNT_CONFIG_PATH = "code/INPUT_HYPERGRYPH_ACCOUNTS.json"
    const val ACCOUNT_CSV_PATH = "code/accounts.csv"
    const val LEGACY_TOKEN_PATH = "code/INPUT_HYPERGRYPH_TOKEN.txt"
    val supportedGames: Set<String> = linkedSetOf("arknights", "endfield")

    fun readCsv(source: String): List<PlatformCsvAccount> =
        PlatformAccountsCsvAdapter.parse(source, setOf(AccountStore.SKLAND))

    fun encodeCsv(accounts: List<PlatformCsvAccount>): String =
        PlatformAccountsCsvAdapter.encode(accounts, setOf(AccountStore.SKLAND))
}
