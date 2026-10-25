package com.example.people.delete

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.example.people.data.local.PeopleDatabase
import com.example.people.data.local.buildPeopleDatabase
import java.io.File
import java.util.UUID

private class TestContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
    override fun getCacheDir(): File = File(System.getProperty("java.io.tmpdir"), "people-test-cache").also { it.mkdirs() }
    override fun getDatabasePath(name: String): File = File(name)
    override fun getSystemService(name: String): Any? = null
}

private val testContext = TestContext()

internal actual fun newDeleteTestDatabasePath(prefix: String): String {
    return File(testContext.cacheDir, "$prefix-${UUID.randomUUID()}.db").absolutePath
}

internal actual fun openDeleteTestDatabase(path: String): PeopleDatabase {
    return buildPeopleDatabase(
        Room.databaseBuilder<PeopleDatabase>(
            context = testContext,
            name = path,
        )
    )
}
