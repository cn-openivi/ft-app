package io.github.rhythmache.spake2.internal.field

internal class GeP2(
    val X: Fe = Fe(),
    val Y: Fe = Fe(),
    val Z: Fe = Fe(),
)

internal class GeP3(
    val X: Fe = Fe(),
    val Y: Fe = Fe(),
    val Z: Fe = Fe(),
    val T: Fe = Fe(),
)

internal class GeP1P1(
    val X: FeLoose = FeLoose(),
    val Y: FeLoose = FeLoose(),
    val Z: FeLoose = FeLoose(),
    val T: FeLoose = FeLoose(),
)

internal class GeCached(
    val YplusX: FeLoose = FeLoose(),
    val YminusX: FeLoose = FeLoose(),
    val Z: FeLoose = FeLoose(),
    val T2d: FeLoose = FeLoose(),
)

internal class GePrecomp(
    val yplusx: FeLoose = FeLoose(),
    val yminusx: FeLoose = FeLoose(),
    val xy2d: FeLoose = FeLoose(),
)
