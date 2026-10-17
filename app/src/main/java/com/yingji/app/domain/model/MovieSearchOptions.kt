package com.yingji.app.domain.model

enum class WatchTimeField(val displayName: String) {
    START_TIME("开始时间"),
    END_TIME("结束时间"),
}

enum class MovieSortOption(val displayName: String) {
    CREATED_DESC("最近添加"),
    CREATED_ASC("最早添加"),
    NAME_ASC("名称排序"),
    WATCH_START_DESC("最近开始观看"),
    WATCH_END_DESC("最近结束观看"),
}
