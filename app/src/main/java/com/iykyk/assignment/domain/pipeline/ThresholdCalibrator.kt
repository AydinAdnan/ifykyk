package com.iykyk.assignment.domain.pipeline

import kotlin.math.roundToInt

/**
 * Chooses the similarity at which two groups of faces are the same person, from evidence
 * the video itself provides.
 *
 * A fixed threshold is the wrong tool here. Published figures for this model family are
 * given in different metrics and disagree once converted - a squared-L2 threshold of 1.1
 * implies a cosine boundary near 0.45, while a normalised-L2 threshold of 0.8 implies
 * 0.68 - and none of them describe this particular export, whose weights are int8
 * quantised and therefore noisier than the float model those numbers were measured on.
 * Tuning the constant by hand just trades splitting one person in four for merging four
 * people into one.
 *
 * The video supplies both labels for free, which is the standard trick in video face
 * clustering:
 *
 *  - two faces visible in the same frame are certainly different people (cannot-link), so
 *    the similarities between them sample the impostor distribution;
 *  - two faces in one tracklet are certainly the same person, because geometric continuity
 *    established it without recognition (must-link), so they sample the genuine
 *    distribution.
 *
 * The threshold is then placed between the two distributions as measured on this footage,
 * with this model, under this lighting.
 */
object ThresholdCalibrator {

    /**
     * Used when the footage yields too little evidence - typically a video where two
     * people are never on screen together. Derived from the squared-L2 threshold of 1.1
     * reported for FaceNet on LFW: for unit vectors cos = 1 - d2/2.
     */
    const val DEFAULT_THRESHOLD = 0.45f

    /** Calibration is never allowed outside this range, however odd the footage. */
    const val MIN_THRESHOLD = 0.30f
    const val MAX_THRESHOLD = 0.62f

    /** Fewer pairs than this is noise rather than a distribution. */
    const val MIN_SAMPLES = 5

    /** Clearance kept from a distribution when only one of the two is available. */
    private const val ONE_SIDED_MARGIN = 0.06f

    /**
     * @param genuine similarities between faces known to be the same person
     * @param impostor similarities between faces known to be different people
     * @param fallback threshold to use when there is not enough evidence to calibrate
     */
    fun calibrate(
        genuine: List<Float>,
        impostor: List<Float>,
        fallback: Float = DEFAULT_THRESHOLD
    ): Float {
        val haveImpostor = impostor.size >= MIN_SAMPLES
        val haveGenuine = genuine.size >= MIN_SAMPLES

        // The tails matter, not the averages: the threshold has to clear the most
        // confusable impostor pair and still admit the weakest genuine one.
        val impostorHigh = if (haveImpostor) percentile(impostor, 0.95f) else null
        val genuineLow = if (haveGenuine) percentile(genuine, 0.10f) else null

        val raw = when {
            impostorHigh != null && genuineLow != null ->
                // When the distributions overlap the embeddings cannot separate these
                // people at any threshold; sitting just above the impostor tail keeps
                // distinct people apart, which is the error a viewer actually sees.
                if (genuineLow > impostorHigh) (impostorHigh + genuineLow) / 2f
                else impostorHigh + ONE_SIDED_MARGIN / 2f

            impostorHigh != null -> impostorHigh + ONE_SIDED_MARGIN
            genuineLow != null -> genuineLow - ONE_SIDED_MARGIN
            else -> fallback
        }

        return raw.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
    }

    /** Linear-rank percentile of [values]; [fraction] is in 0..1. */
    fun percentile(values: List<Float>, fraction: Float): Float {
        require(values.isNotEmpty()) { "percentile of an empty sample" }
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * fraction.coerceIn(0f, 1f)).roundToInt()
        return sorted[index]
    }
}
