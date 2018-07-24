/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.daemon

import com.intellij.util.containers.StringInterner
import gnu.trove.THashSet
import org.jetbrains.kotlin.daemon.common.CompilerCallbackServicesFacade
import org.jetbrains.kotlin.daemon.common.DummyProfiler
import org.jetbrains.kotlin.daemon.common.Profiler
import org.jetbrains.kotlin.incremental.components.LookupInfo
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.components.Position
import org.jetbrains.kotlin.incremental.components.ScopeKind
import org.jetbrains.kotlin.name.FqName


class RemoteLookupTrackerClient(
    val facade: CompilerCallbackServicesFacade,
    eventManager: EventManager,
    val profiler: Profiler = DummyProfiler()
) : LookupTracker {
    private val isDoNothing = profiler.withMeasure(this) { facade.lookupTracker_isDoNothing() }

    private data class CompressedLookupInfo(
        val filePath: String,
        val symbolFqName: String,
        val scopeKind: Byte
    ) {
        fun toLookupInfo(): LookupInfo {
            val fqName = FqName(symbolFqName)
            assert(!fqName.isRoot) { "CompressedLookupInfo::symbolFqName should not be a root" }

            val scopeKind = ScopeKind.values()[scopeKind.toInt()]

            return LookupInfo(filePath, Position.NO_POSITION, fqName.parent().asString(), scopeKind, fqName.shortName().asString())
        }
    }

    private val lookups = THashSet<Any>()
    private val interner = StringInterner()

    override val requiresPosition: Boolean = profiler.withMeasure(this) { facade.lookupTracker_requiresPosition() }

    override fun record(filePath: String, position: Position, scopeFqName: String, scopeKind: ScopeKind, name: String) {
        if (isDoNothing) return

        if (requiresPosition) {
            // Do not perform any optimizations when `requiresPosition` is true
            // Currently, it's only used for tests
            lookups.add(LookupInfo(filePath, position, scopeFqName, scopeKind, name))
            return
        }

        val internedFilePath = interner.intern(filePath)
        val internedSymbolFqName = interner.intern("$scopeFqName.$name")

        lookups.add(CompressedLookupInfo(internedFilePath, internedSymbolFqName, scopeKind.ordinal.toByte()))
    }

    init {
        eventManager.onCompilationFinished { flush() }
    }

    private fun flush() {
        if (isDoNothing || lookups.isEmpty()) return

        profiler.withMeasure(this) {
            facade.lookupTracker_record(
                lookups.map {
                    if (requiresPosition)
                        it as LookupInfo
                    else
                        (it as CompressedLookupInfo).toLookupInfo()
                }
            )
        }

        lookups.clear()
    }
}
