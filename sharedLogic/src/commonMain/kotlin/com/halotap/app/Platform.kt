package com.halotap.app

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
