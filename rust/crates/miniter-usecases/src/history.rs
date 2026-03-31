use crate::commands::EditCommand;

#[derive(Debug, Clone)]
pub struct History {
    undo_stack: Vec<EditCommand>,
    redo_stack: Vec<EditCommand>,
    capacity: usize,
}

impl History {
    pub fn new(capacity: usize) -> Self {
        Self {
            undo_stack: Vec::with_capacity(capacity),
            redo_stack: Vec::new(),
            capacity,
        }
    }

    pub fn push(&mut self, inverse: EditCommand) {
        if self.undo_stack.len() >= self.capacity {
            self.undo_stack.remove(0);
        }
        self.undo_stack.push(inverse);
        self.redo_stack.clear();
    }

    pub fn push_redo(&mut self, cmd: EditCommand) {
        self.redo_stack.push(cmd);
    }

    pub fn pop_undo(&mut self) -> Option<EditCommand> {
        self.undo_stack.pop()
    }

    pub fn pop_redo(&mut self) -> Option<EditCommand> {
        self.redo_stack.pop()
    }

    pub fn can_undo(&self) -> bool {
        !self.undo_stack.is_empty()
    }

    pub fn can_redo(&self) -> bool {
        !self.redo_stack.is_empty()
    }

    pub fn undo_depth(&self) -> usize {
        self.undo_stack.len()
    }

    pub fn redo_depth(&self) -> usize {
        self.redo_stack.len()
    }

    pub fn clear(&mut self) {
        self.undo_stack.clear();
        self.redo_stack.clear();
    }
}
