package atm.bloodworkxgaming.serverstarter.mirror.download

/** URL 前缀重写规则：命中 prefix（字符串前缀）则替换为 replacement，保留剩余路径。 */
data class MirrorRule(val prefix: String, val replacement: String)