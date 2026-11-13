package sh.haven.app

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.et.EtSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.smb.SmbClient
import sh.haven.core.smb.SmbSessionManager
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ssh.sftp.ListResult
import sh.haven.core.ssh.sftp.SftpSession
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "HavenDocProvider"

/** Exposes connected SFTP and SMB filesystems through Android's system file picker. */
class HavenDocumentsProvider : DocumentsProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderEntryPoint {
        fun sshSessionManager(): SshSessionManager
        fun moshSessionManager(): MoshSessionManager
        fun etSessionManager(): EtSessionManager
        fun smbSessionManager(): SmbSessionManager



        fun connectionRepository(): ConnectionRepository
    }

    internal data class RootDescriptor(
        val rootId: String,
        val documentId: String,
        val title: String,
        val summary: String,
    )

    internal data class DocDescriptor(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val lastModifiedMs: Long,
        val flags: Int,
    )

    private val entryPoint: ProviderEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            context!!.applicationContext,
            ProviderEntryPoint::class.java,
        )
    }

    companion object {
        private val AUTHORITY = BuildConfig.APPLICATION_ID + ".provider"



        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
        )

        private val DOC_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

        // Document ID format: "profileId:/path"
        // Root document ID: "profileId:/"
        internal fun encodeDocId(profileId: String, path: String): String {
            val normPath = if (path.startsWith("/")) path else "/$path"
            return "$profileId:$normPath"
        }

        internal fun decodeDocId(docId: String): Pair<String, String> {
            val colon = docId.indexOf(':')
            if (colon < 0) return docId to "/"
            val rawPath = docId.substring(colon + 1)
            val normPath = when {
                rawPath.isEmpty() -> "/"
                rawPath.startsWith("/") -> rawPath
                else -> "/$rawPath"
            }
            return docId.substring(0, colon) to normPath
        }




    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: ROOT_PROJECTION
        val cursor = MatrixCursor(cols)
        val roots = collectRootDescriptors(entryPoint, context!!.filesDir)
        for (root in roots) {
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, root.rootId)
                add(Root.COLUMN_DOCUMENT_ID, root.documentId)
                add(Root.COLUMN_TITLE, root.title)
                add(Root.COLUMN_SUMMARY, root.summary)
                add(
                    Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD,
                )
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_MIME_TYPES, "*/*")
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: DOC_PROJECTION
        val cursor = MatrixCursor(cols)
        val doc = queryDocDescriptor(documentId, entryPoint, context!!.filesDir)
        if (doc != null) {
            addDescriptorRow(cursor, doc)
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cols = projection ?: DOC_PROJECTION
        val cursor = MatrixCursor(cols)
        val children = queryChildDescriptors(parentDocumentId, entryPoint, context!!.filesDir)
        for (doc in children) {
            addDescriptorRow(cursor, doc)
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val (profileId, path) = decodeDocId(documentId)
        val isWrite = mode.contains("w")





        val cacheFile = File(context!!.cacheDir, "haven_doc_${documentId.hashCode()}")




        // 3. SMB share
        val smbClient = getSmbClient(profileId, entryPoint)
        if (smbClient != null) {
            if (isWrite) {
                if (mode.contains("r") && !mode.contains("t")) {
                    runCatching {
                        FileOutputStream(cacheFile).use { out ->
                            smbClient.download(path, out) { _, _ -> }
                        }
                    }
                } else {
                    cacheFile.delete()
                }
                return openWritableSmbFile(smbClient, path, cacheFile)
            }
            FileOutputStream(cacheFile).use { out ->
                smbClient.download(path, out) { _, _ -> }
            }
            return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // 4. SFTP (SSH / Mosh / ET)
        val session = getSftpSession(profileId, entryPoint)
            ?: throw IllegalStateException("Not connected: $profileId")
        if (isWrite) {
            if (mode.contains("r") && !mode.contains("t")) {
                runCatching {
                    FileOutputStream(cacheFile).use { out ->
                        runBlocking { session.download(path, out) { _, _ -> } }
                    }
                }
            } else {
                cacheFile.delete()
            }
            return openWritableSftpFile(session, path, cacheFile)
        }
        FileOutputStream(cacheFile).use { out ->
            runBlocking { session.download(path, out) { _, _ -> } }
        }
        return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val newDocId = createDoc(
            parentDocumentId = parentDocumentId,
            mimeType = mimeType,
            displayName = displayName,
            ep = entryPoint,
            filesDir = context!!.filesDir,
            cacheDir = context!!.cacheDir,
        )
        notifyChange(newDocId)
        return newDocId
    }

    override fun deleteDocument(documentId: String) {
        deleteDoc(documentId, entryPoint, context!!.filesDir)
        notifyChange(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val newDocId = renameDoc(documentId, displayName, entryPoint, context!!.filesDir)
        notifyChange(documentId)
        notifyChange(newDocId)
        return newDocId
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (parentProfile, parentPath) = decodeDocId(parentDocumentId)
        val (childProfile, childPath) = decodeDocId(documentId)
        if (parentProfile != childProfile) return false
        val normalParent = parentPath.trimEnd('/') + "/"
        return childPath.startsWith(normalParent)
    }

    override fun getDocumentType(documentId: String): String {
        val (_, path) = decodeDocId(documentId)
        if (path == "/" || path.endsWith("/")) return Document.MIME_TYPE_DIR
        return getMimeType(path)
    }

    // --- internal testable operations ---

    internal fun collectRootDescriptors(
        ep: ProviderEntryPoint,
        filesDir: File,
    ): List<RootDescriptor> {
        val roots = mutableListOf<RootDescriptor>()

        val ssh = ep.sshSessionManager()
        val mosh = ep.moshSessionManager()
        val et = ep.etSessionManager()
        val smb = ep.smbSessionManager()



        // 1. SFTP (SSH / Mosh / ET)
        val sftpProfileIds = linkedSetOf<String>()
        ssh.sessions.value.values
            .filter { it.status == SshSessionManager.SessionState.Status.CONNECTED }
            .forEach { sftpProfileIds.add(it.profileId) }
        mosh.sessions.value.values
            .filter {
                it.status == MoshSessionManager.SessionState.Status.CONNECTED &&
                    it.sshClient != null
            }
            .forEach { sftpProfileIds.add(it.profileId) }
        et.sessions.value.values
            .filter {
                it.status == EtSessionManager.SessionState.Status.CONNECTED &&
                    it.sshClient != null
            }
            .forEach { sftpProfileIds.add(it.profileId) }

        val sshLabels = ssh.sessions.value.values.associate { it.profileId to it.label }
        val moshLabels = mosh.sessions.value.values.associate { it.profileId to it.label }
        val etLabels = et.sessions.value.values.associate { it.profileId to it.label }

        for (profileId in sftpProfileIds) {
            val label = sshLabels[profileId]
                ?: moshLabels[profileId]
                ?: etLabels[profileId]
                ?: profileId
            roots.add(
                RootDescriptor(
                    rootId = profileId,
                    documentId = encodeDocId(profileId, "/"),
                    title = label,
                    summary = "SFTP",
                ),
            )
        }

        // 2. SMB
        val smbSessions = smb.sessions.value.values
            .filter { it.status == SmbSessionManager.SessionState.Status.CONNECTED }
            .distinctBy { it.profileId }
        for (session in smbSessions) {
            roots.add(
                RootDescriptor(
                    rootId = session.profileId,
                    documentId = encodeDocId(session.profileId, "/"),
                    title = session.label.ifEmpty { session.profileId },
                    summary = "SMB",
                ),
            )
        }

        return roots
    }

    internal fun queryDocDescriptor(
        documentId: String,
        ep: ProviderEntryPoint,
        filesDir: File,
    ): DocDescriptor? {
        val (profileId, path) = decodeDocId(documentId)
        if (path == "/") {
            if (collectRootDescriptors(ep, filesDir).none { it.rootId == profileId }) return null
            return DocDescriptor(
                documentId = encodeDocId(profileId, "/"),
                displayName = getRootLabel(profileId, ep),
                mimeType = Document.MIME_TYPE_DIR,
                size = 0L,
                lastModifiedMs = 0L,
                flags = Document.FLAG_DIR_SUPPORTS_CREATE,
            )
        }







        val smbClient = getSmbClient(profileId, ep)
        if (smbClient != null) {
            val parentPath = path.trimEnd('/').substringBeforeLast('/', "/")
            val name = path.trimEnd('/').substringAfterLast('/')
            val entry = smbClient.listDirectory(parentPath).firstOrNull { it.name == name }
                ?: return null
            return buildDocDescriptor(
                profileId = profileId,
                name = entry.name,
                path = entry.path,
                isDirectory = entry.isDirectory,
                size = entry.size,
                modifiedTimeSeconds = entry.modifiedTime,
            )
        }

        val session = getSftpSession(profileId, ep) ?: return null
        val name = path.trimEnd('/').substringAfterLast('/')
        return try {
            val attrs = runBlocking { session.stat(path) }
            buildDocDescriptor(
                profileId = profileId,
                name = name,
                path = path,
                isDirectory = attrs.isDirectory,
                size = attrs.size,
                modifiedTimeSeconds = attrs.modifiedTimeSeconds.toLong(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "stat failed: $path", e)
            null
        }
    }

    internal fun queryChildDescriptors(
        parentDocumentId: String,
        ep: ProviderEntryPoint,
        filesDir: File,
    ): List<DocDescriptor> {
        val (profileId, path) = decodeDocId(parentDocumentId)







        val smbClient = getSmbClient(profileId, ep)
        if (smbClient != null) {
            return try {
                smbClient.listDirectory(path).map { entry ->
                    buildDocDescriptor(
                        profileId = profileId,
                        name = entry.name,
                        path = entry.path,
                        isDirectory = entry.isDirectory,
                        size = entry.size,
                        modifiedTimeSeconds = entry.modifiedTime,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "SMB listDirectory failed: $path", e)
                emptyList()
            }
        }

        val session = getSftpSession(profileId, ep) ?: return emptyList()
        return try {
            data class PendingEntry(
                val name: String,
                val path: String,
                val isLink: Boolean,
                val isDir: Boolean,
                val size: Long,
                val mTime: Long,
            )
            val pending = mutableListOf<PendingEntry>()
            runBlocking {
                session.list(path) { attrs ->
                    val childPath = path.trimEnd('/') + "/" + attrs.filename
                    pending.add(
                        PendingEntry(
                            attrs.filename,
                            childPath,
                            attrs.isSymlink,
                            attrs.isDirectory,
                            attrs.size,
                            attrs.modifiedTimeSeconds.toLong(),
                        ),
                    )
                    ListResult.CONTINUE
                }
            }
            pending.map { entry ->
                val isDir = if (entry.isLink && !entry.isDir) {
                    try {
                        runBlocking { session.stat(entry.path).isDirectory }
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    entry.isDir
                }
                buildDocDescriptor(
                    profileId = profileId,
                    name = entry.name,
                    path = entry.path,
                    isDirectory = isDir,
                    size = entry.size,
                    modifiedTimeSeconds = entry.mTime,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "SFTP ls failed: $path", e)
            emptyList()
        }
    }

    internal fun createDoc(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
        ep: ProviderEntryPoint,
        filesDir: File,
        cacheDir: File = filesDir,
    ): String {
        val (profileId, parentPath) = decodeDocId(parentDocumentId)
        val childPath = parentPath.trimEnd('/') + "/" + displayName
        val isDir = mimeType == Document.MIME_TYPE_DIR







        val smbClient = getSmbClient(profileId, ep)
        if (smbClient != null) {
            if (isDir) {
                smbClient.mkdir(childPath)
            } else {
                "".byteInputStream().use { input ->
                    smbClient.upload(input, childPath, 0) { _, _ -> }
                }
            }
            return encodeDocId(profileId, childPath)
        }

        val session = getSftpSession(profileId, ep)
            ?: throw IllegalStateException("Not connected: $profileId")
        runBlocking {
            if (isDir) {
                session.mkdir(childPath)
            } else {
                "".byteInputStream().use { input ->
                    session.upload(input, 0L, childPath) { _, _ -> }
                }
            }
        }
        return encodeDocId(profileId, childPath)
    }

    internal fun deleteDoc(
        documentId: String,
        ep: ProviderEntryPoint,
        filesDir: File,
    ) {
        val (profileId, path) = decodeDocId(documentId)







        val smbClient = getSmbClient(profileId, ep)
        if (smbClient != null) {
            val parentPath = path.trimEnd('/').substringBeforeLast('/', "/")
            val name = path.trimEnd('/').substringAfterLast('/')
            val entries = smbClient.listDirectory(parentPath)
            val isDir = entries.firstOrNull { it.name == name }?.isDirectory == true
            smbClient.delete(path, isDir)
            return
        }

        val session = getSftpSession(profileId, ep)
            ?: throw IllegalStateException("Not connected: $profileId")
        runBlocking {
            val attrs = session.stat(path)
            sh.haven.feature.sftp.transport.SftpTransport({ session })
                .delete(path, attrs.isDirectory && !attrs.isSymlink)
        }
    }

    internal fun renameDoc(
        documentId: String,
        displayName: String,
        ep: ProviderEntryPoint,
        filesDir: File,
    ): String {
        val (profileId, path) = decodeDocId(documentId)
        val parentPath = path.trimEnd('/').substringBeforeLast('/', "")
        val newPath = "$parentPath/$displayName"







        val smbClient = getSmbClient(profileId, ep)
        if (smbClient != null) {
            smbClient.rename(path, newPath)
            return encodeDocId(profileId, newPath)
        }

        val session = getSftpSession(profileId, ep)
            ?: throw IllegalStateException("Not connected: $profileId")
        runBlocking {
            session.rename(path, newPath)
        }
        return encodeDocId(profileId, newPath)
    }

    // --- helpers ---

    private fun addDescriptorRow(cursor: MatrixCursor, doc: DocDescriptor) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, doc.documentId)
            add(Document.COLUMN_DISPLAY_NAME, doc.displayName)
            add(Document.COLUMN_MIME_TYPE, doc.mimeType)
            add(Document.COLUMN_SIZE, doc.size)
            add(Document.COLUMN_LAST_MODIFIED, doc.lastModifiedMs)
            add(Document.COLUMN_FLAGS, doc.flags)
        }
    }

    internal fun buildDocDescriptor(
        profileId: String,
        name: String,
        path: String,
        isDirectory: Boolean,
        size: Long,
        modifiedTimeSeconds: Long,
        explicitMimeType: String = "",
    ): DocDescriptor {
        val docId = encodeDocId(profileId, path)
        val mime = when {
            isDirectory -> Document.MIME_TYPE_DIR
            explicitMimeType.isNotEmpty() && !explicitMimeType.endsWith("/directory") -> explicitMimeType
            else -> getMimeType(name)
        }
        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        if (isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        return DocDescriptor(
            documentId = docId,
            displayName = name,
            mimeType = mime,
            size = size,
            lastModifiedMs = modifiedTimeSeconds * 1000L,
            flags = flags,
        )
    }

    private fun getMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    internal fun getRootLabel(profileId: String, ep: ProviderEntryPoint): String =
        ep.sshSessionManager().sessions.value.values.firstOrNull { it.profileId == profileId }?.label
            ?: ep.moshSessionManager().sessions.value.values.firstOrNull { it.profileId == profileId }?.label
            ?: ep.etSessionManager().sessions.value.values.firstOrNull { it.profileId == profileId }?.label
            ?: ep.smbSessionManager().sessions.value.values.firstOrNull { it.profileId == profileId }?.label
            ?: profileId







    private fun getSmbClient(profileId: String, ep: ProviderEntryPoint): SmbClient? {
        return ep.smbSessionManager().getClientForProfile(profileId)
    }

    private fun getSftpSession(profileId: String, ep: ProviderEntryPoint): SftpSession? {
        val ssh = ep.sshSessionManager()
        val mosh = ep.moshSessionManager()
        val et = ep.etSessionManager()

        return ssh.openSftpSession(profileId)
            ?: (mosh.getSshClientForProfile(profileId) as? SshClient)
                ?.openSftpSession()
            ?: (et.getSshClientForProfile(profileId) as? SshClient)
                ?.openSftpSession()
    }



    private fun openWritableSmbFile(
        client: SmbClient,
        remotePath: String,
        cacheFile: File,
    ): ParcelFileDescriptor {
        val handler = android.os.Handler(context!!.mainLooper)
        return ParcelFileDescriptor.open(
            cacheFile,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE,
            handler,
        ) { e ->
            // Called when the writing app closes the file descriptor
            if (e == null) {
                try {
                    cacheFile.inputStream().use { input ->
                        client.upload(input, remotePath, cacheFile.length()) { _, _ -> }
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "SMB write-back failed: $remotePath", ex)
                }
            }
            cacheFile.delete()
        }
    }

    private fun openWritableSftpFile(
        session: SftpSession,
        remotePath: String,
        cacheFile: File,
    ): ParcelFileDescriptor {
        val handler = android.os.Handler(context!!.mainLooper)
        return ParcelFileDescriptor.open(
            cacheFile,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE,
            handler,
        ) { e ->
            if (e == null) {
                try {
                    cacheFile.inputStream().use { input ->
                        runBlocking {
                            session.upload(input, cacheFile.length(), remotePath) { _, _ -> }
                        }
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "SFTP write-back failed: $remotePath", ex)
                }
            }
            cacheFile.delete()
        }
    }

    private fun notifyChange(docId: String) {
        val uri = DocumentsContract.buildDocumentUri(AUTHORITY, docId)
        context?.contentResolver?.notifyChange(uri, null)
    }
}
