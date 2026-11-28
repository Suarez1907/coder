package com.suarez.coder.formal

/**
 * Faz 8A — Karşı-Örnek Güdümlü Sentez (`CegisVerifier`).
 *
 * Saf Kotlin kısıt ve sınır karşı-örnek üreticisi (`ConstraintCounterexampleFinder`) ile
 * kritik sayısal/mantıksal sözleşmeleri (`Pricing`, `KellyBudgetMarket`, `SearchReplace`,
 * `Turn0ContextPacker`) sıfır yerel native bağımlılıkla ve `$0` LLM maliyetiyle doğrular.
 */
object CegisVerifier {

    data class Counterexample(
        val contractName: String,
        val inputs: Map<String, Any?>,
        val violatedInvariant: String,
        val actualOutput: Any?,
    ) {
        fun repairPrompt(): String =
            "Ürettiğin kod şu somut girdide '$contractName' sözleşmesini kırıyor: $inputs -> $actualOutput " +
                "(İhlal: $violatedInvariant). Yalnızca bu karşı-örneği ve önceki tüm testleri geçecek şekilde @HOLE gövdesini güncelle."
    }

    data class VerificationReport(
        val contractName: String,
        val probesTested: Int,
        val counterexample: Counterexample?,
    ) {
        val verified: Boolean get() = counterexample == null
    }

    val NUMERIC_BOUNDARIES: List<Double> = listOf(
        0.0, -1.0, 1.0, -0.0, 0.0001, 0.05, 0.70, 100.0, 1e6,
        Double.MIN_VALUE, Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
    )

    val INT_BOUNDARIES: List<Int> = listOf(0, -1, 1, 2, 16, 1000, Int.MIN_VALUE, Int.MAX_VALUE)

    val STRING_BOUNDARIES: List<String> = listOf("", " ", "\n\t", "çğıöşüÇĞIÖŞÜ", "a".repeat(2048))

    /**
     * Checks a 1-argument numeric contract over standard boundaries + interval bisection around transitions.
     */
    fun verifyNumericContract(
        contractName: String,
        invariantDescription: String,
        fn: (Double) -> Double,
        predicate: (input: Double, output: Double) -> Boolean,
    ): VerificationReport {
        var tested = 0
        for (x in NUMERIC_BOUNDARIES) {
            tested++
            val out = runCatching { fn(x) }.getOrElse { ex ->
                return VerificationReport(
                    contractName,
                    tested,
                    Counterexample(contractName, mapOf("x" to x), "$invariantDescription (exception: ${ex.message})", ex.toString()),
                )
            }
            if (!predicate(x, out)) {
                return VerificationReport(
                    contractName,
                    tested,
                    Counterexample(contractName, mapOf("x" to x), invariantDescription, out),
                )
            }
        }
        // Interval bisection scan over [0.0, 1.0] and [1.0, 1000.0]
        for (mid in listOf(0.5, 0.25, 0.75, 0.125, 0.875, 500.0, 250.0, 750.0)) {
            tested++
            val out = runCatching { fn(mid) }.getOrElse { ex ->
                return VerificationReport(
                    contractName,
                    tested,
                    Counterexample(contractName, mapOf("x" to mid), invariantDescription, ex.toString()),
                )
            }
            if (!predicate(mid, out)) {
                return VerificationReport(
                    contractName,
                    tested,
                    Counterexample(contractName, mapOf("x" to mid), invariantDescription, out),
                )
            }
        }
        return VerificationReport(contractName, tested, null)
    }

    /**
     * Closed-loop CEGIS (`maxRounds = 2`): runs verifier, feeds concrete counterexample back to synthesizer.
     */
    fun <S> runCegisLoop(
        initialCandidate: S,
        maxRounds: Int = 2,
        verify: (S) -> VerificationReport,
        synthesizeFromCounterexample: (S, Counterexample, Int) -> S,
    ): Pair<S, VerificationReport> {
        var current = initialCandidate
        var report = verify(current)
        var round = 1
        while (!report.verified && round <= maxRounds) {
            current = synthesizeFromCounterexample(current, report.counterexample!!, round)
            report = verify(current)
            round++
        }
        return current to report
    }
}
