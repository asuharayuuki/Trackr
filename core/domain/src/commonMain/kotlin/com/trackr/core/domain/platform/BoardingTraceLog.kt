package com.trackr.core.domain.platform

/**
 * 「上车后自动结束追踪」的判定记录（issue #49）：判上车那一小段里，每一轮轮询记一行——当时的定位、车的位置、
 * 判定前后的状态和结论，只存在本机。自动结束没生效时，用户在「我的」里导出这份记录，就能看出断在哪一环，
 * 也能拿它做回放测试。
 *
 * 记录里有追踪时的手机位置，所以不上传、不参与备份，只有用户自己点「导出」才会离开手机。
 */
interface BoardingTraceLog {
    /** 追加一行（一条 JSON）。不阻塞调用方，写失败只丢这一行 */
    fun record(line: String)

    /**
     * 把记录交给系统分享面板。
     * @return 还没有记录、或分享面板打不开时为 false
     */
    suspend fun share(): Boolean
}

/** 不记录（iOS、测试的默认值） */
object NoBoardingTraceLog : BoardingTraceLog {
    override fun record(line: String) = Unit
    override suspend fun share(): Boolean = false
}
