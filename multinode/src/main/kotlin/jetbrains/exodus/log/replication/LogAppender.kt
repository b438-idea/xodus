/**
 * Copyright 2010 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.log.replication

import jetbrains.exodus.ExodusException
import jetbrains.exodus.log.Log
import jetbrains.exodus.log.LogTip
import mu.KLogging
import org.jetbrains.annotations.NotNull

object LogAppender : KLogging() {

    @JvmStatic
    @JvmOverloads
    fun appendLog(log: Log, delta: LogReplicationDelta, fileFactory: FileFactory, currentTip: LogTip = log.beginWrite()): () -> LogTip {
        try {
            checkPreconditions(log, currentTip, delta)

            val highAddress = delta.highAddress
            if (delta.files.isEmpty()) {
                // truncate log
                log.abortWrite()
                return {
                    log.setHighAddress(currentTip, highAddress)
                }
            } else {
                val currentHighFile = log.getFileAddress(currentTip.highAddress)

                if (delta.files.first() != currentHighFile) {
                    // current file has been deleted, just pad it with nulls to respect log constraints
                    log.padWithNulls()
                }

                writeFiles(log, currentTip, delta, fileFactory)

                return {
                    log.endWrite()
                }
            }
        } catch (t: Throwable) {
            log.revertWrite(currentTip) // rollback potential padding and created files

            throw ExodusException.toExodusException(t, "Failed to replicate log")
        }
    }

    private fun checkPreconditions(log: @NotNull Log, currentTip: LogTip, delta: LogReplicationDelta) {
        if (delta.startAddress != currentTip.highAddress || delta.fileSize != log.fileSize) {
            throw IllegalArgumentException("Non-matching replication delta")
        }
    }

    private fun writeFiles(
            log: Log,
            currentTip: LogTip,
            delta: LogReplicationDelta,
            fileFactory: FileFactory
    ) {
        val fileSize = log.fileSize

        val lastFile = log.getFileAddress(delta.highAddress)

        var lastFileWrite: WriteResult? = null

        var prevAddress = log.getNextFileAddress(currentTip.highAddress)

        for (file in delta.files) {
            if (file <= prevAddress) {
                throw IllegalStateException("Incorrect file order")
            }
            prevAddress = file

            val atLastFile = file == lastFile
            val expectedLength = if (atLastFile) {
                delta.highAddress - file
            } else {
                fileSize
            }

            val useLastPage = atLastFile && expectedLength != fileSize
            val created = fileFactory.fetchFile(log, file, expectedLength, useLastPage)

            if (created.written != expectedLength) {
                throw IllegalStateException("Fetched unexpected bytes")
            }

            if (useLastPage && (delta.highAddress - log.getHighPageAddress(delta.highAddress) != created.lastPageWritten.toLong())) {
                throw IllegalStateException("Fetched unexpected last page bytes")
            }

            if (atLastFile) {
                lastFileWrite = created
            }
        }
        if (lastFileWrite == null) {
            throw IllegalArgumentException("Last file is not provided")
        }
    }
}
