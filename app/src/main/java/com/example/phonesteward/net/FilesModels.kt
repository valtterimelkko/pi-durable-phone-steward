package com.example.phonesteward.net

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Wire models for the A10.1 file tools. Paths are relative to the primary shared storage root
 * (`""` is the root), with `/` separators.
 */

/** One file or folder. A folder's `sizeBytes` and `fileCount` are recursive totals; a file has no `fileCount`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FileEntry(
    val path: String,
    val type: String,
    val sizeBytes: Long = 0,
    val modifiedMs: Long = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fileCount: Long? = null,
    /** True only for folders Android will not let an app read (Android/data, Android/obb). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val unreadable: Boolean? = null,
)

/** `files_query` params: one of `list`, `find`, `du` (the fields of the other ops stay at their defaults). */
@Serializable
data class FilesQueryParams(
    val op: String,
    val path: String = "",
    val depth: Int = 1,
    val root: String = "",
    val namePattern: String? = null,
    val type: String = "any",
    val minBytes: Long = 0,
    val olderThanDays: Int? = null,
    val limit: Int = 200,
    val paths: List<String> = emptyList(),
)

@Serializable
data class FilesListData(val path: String, val entries: List<FileEntry>, val truncated: Boolean)

@Serializable
data class FilesFindData(val items: List<FileEntry>, val truncated: Boolean)

@Serializable
data class DuPath(
    val path: String,
    /** `file` or `dir`; with [modifiedMs] it lets a `delete_files` item completed from a `du`-only report pass the identity check (A10.1). */
    val type: String,
    val bytes: Long,
    val files: Long,
    val dirs: Long,
    val modifiedMs: Long,
    val unreadable: Boolean,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FilesDuData(
    val paths: List<DuPath>,
    /** Present (true) only when the time budget dropped a path. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val truncated: Boolean? = null,
)
