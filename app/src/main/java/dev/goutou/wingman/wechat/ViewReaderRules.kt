package dev.goutou.wingman.wechat

/** Pure view classification rules. Android-specific code only supplies these measurements. */
data class ImageCandidateSpec(
    val width: Int,
    val height: Int,
    val depth: Int,
    val isLeaf: Boolean,
    val isAvatarLike: Boolean,
    val isSquareSmall: Boolean,
    val isBig: Boolean,
)

/** Selects the image candidate without depending on View, Bitmap, or Canvas. */
fun selectImageCandidate(candidates: List<ImageCandidateSpec>, rowIndex: Int = -1): Int {
    val bigLeaf = candidates.indices.filter { candidates[it].isBig && candidates[it].isLeaf }
    bigLeaf.maxByOrNull { candidates[it].width * candidates[it].height }?.let { return it }

    val bigContainer = candidates.indices.filter { candidates[it].isBig && it != rowIndex }
    bigContainer.maxWithOrNull(compareBy<Int> { candidates[it].depth * 10_000_000 + candidates[it].width * candidates[it].height })
        ?.let { return it }

    return candidates.indices
        .filter { candidates[it].isLeaf && !candidates[it].isAvatarLike && !candidates[it].isSquareSmall }
        .maxByOrNull { candidates[it].width * candidates[it].height } ?: -1
}
