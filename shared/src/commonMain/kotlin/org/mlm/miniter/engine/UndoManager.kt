package org.mlm.miniter.engine

import org.mlm.miniter.project.MinterProject

class UndoManager(private val maxHistory: Int = 50) {

    private val undoStack = ArrayDeque<MinterProject>()
    private val redoStack = ArrayDeque<MinterProject>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    fun push(currentState: MinterProject) {
        undoStack.addLast(currentState)
        if (undoStack.size > maxHistory) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    fun undo(currentState: MinterProject): MinterProject? {
        if (undoStack.isEmpty()) return null
        redoStack.addLast(currentState)
        return undoStack.removeLast()
    }

    fun redo(currentState: MinterProject): MinterProject? {
        if (redoStack.isEmpty()) return null
        undoStack.addLast(currentState)
        return redoStack.removeLast()
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
