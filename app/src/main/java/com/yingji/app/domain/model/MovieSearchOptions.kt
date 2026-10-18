package com.yingji.app.domain.model

enum class WatchTimeField(val displayName: String) {
    START_TIME("开始时间"),
    END_TIME("结束时间"),
}

enum class MovieSortOption(val displayName: String) {
    CREATED_DESC("添加时间降序"),
    CREATED_ASC("添加时间升序"),
    NAME_ASC("名称升序"),
    WATCH_START_DESC("观看开始降序"),
    WATCH_END_DESC("观看结束降序"),
}
