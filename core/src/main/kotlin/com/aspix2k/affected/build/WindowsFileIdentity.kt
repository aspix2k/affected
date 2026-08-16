package com.aspix2k.affected.build

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import java.nio.file.Path

internal fun windowsFileIdentity(path: Path): String? {
    if (!System.getProperty("os.name").startsWith("Windows")) return null
    val handle = Kernel32.INSTANCE.CreateFile(
        path.toString(),
        WinNT.FILE_READ_ATTRIBUTES,
        WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE,
        null,
        WinNT.OPEN_EXISTING,
        WinNT.FILE_FLAG_BACKUP_SEMANTICS or WinNT.FILE_FLAG_OPEN_REPARSE_POINT,
        null,
    )
    if (WinBase.INVALID_HANDLE_VALUE == handle) return null
    return try {
        val information = WinBase.FILE_ID_INFO()
        val read = Kernel32.INSTANCE.GetFileInformationByHandleEx(
            handle,
            WinBase.FileIdInfo,
            information.pointer,
            WinDef.DWORD(information.size().toLong()),
        )
        if (!read) return null
        information.read()
        val identifier = information.FileId.Identifier.joinToString("") { byte ->
            "%02x".format(byte.toByte().toInt() and 0xff)
        }
        "${information.VolumeSerialNumber.toULong().toString(16)}:$identifier"
    } finally {
        Kernel32.INSTANCE.CloseHandle(handle)
    }
}
