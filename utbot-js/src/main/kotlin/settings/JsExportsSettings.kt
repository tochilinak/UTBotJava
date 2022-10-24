package settings

object JsExportsSettings {

    // Anchors for exports in users code. Used in regexes to modify this section on demand.
    const val startComment = "// Start of exports generated by UTBot"
    const val endComment = "// End of exports generated by UTBot"
}