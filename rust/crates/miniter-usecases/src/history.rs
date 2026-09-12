//! Transactions, coalescing, undo/redo (from renamite-history).

use crate::commands::EditCommand;

/// One undo step: the commands plus their apply-time inverses.
#[derive(Debug, Clone)]
pub struct Transaction {
    pub label: String,
    forward: Vec<EditCommand>,
    inverse: Vec<EditCommand>,
}

impl Transaction {
    pub fn forward(&self) -> &[EditCommand] {
        &self.forward
    }

    pub fn inverse(&self) -> &[EditCommand] {
        &self.inverse
    }
}

/// Structural coalescing for live-drag edits: same variant on the same target
/// (plus index for keyed updates) folds by replacing the stored forward value.
/// The already-recorded inverse (pre-gesture state) is kept by the caller.
fn coalesce(last: &EditCommand, new: &EditCommand) -> bool {
    use EditCommand::*;
    match (last, new) {
        (TrimClipStart { clip_id, .. }, TrimClipStart { clip_id: nid, .. }) => clip_id == nid,
        (TrimClipEnd { clip_id, .. }, TrimClipEnd { clip_id: nid, .. }) => clip_id == nid,
        (MoveClip { clip_id, .. }, MoveClip { clip_id: nid, .. }) => clip_id == nid,
        (SetClipSpeed { clip_id, .. }, SetClipSpeed { clip_id: nid, .. }) => clip_id == nid,
        (SetClipVolume { clip_id, .. }, SetClipVolume { clip_id: nid, .. }) => clip_id == nid,
        (SetClipOpacity { clip_id, .. }, SetClipOpacity { clip_id: nid, .. }) => clip_id == nid,
        (UpdateTextContent { clip_id, .. }, UpdateTextContent { clip_id: nid, .. }) => {
            clip_id == nid
        }
        (UpdateTextStyle { clip_id, .. }, UpdateTextStyle { clip_id: nid, .. }) => clip_id == nid,
        (RenameTrack { track_id, .. }, RenameTrack { track_id: nid, .. }) => track_id == nid,
        (
            UpdateKeyframe { clip_id, index, .. },
            UpdateKeyframe {
                clip_id: nid,
                index: nindex,
                ..
            },
        ) => clip_id == nid && index == nindex,
        (
            UpdateVideoFilter { clip_id, index, .. },
            UpdateVideoFilter {
                clip_id: nid,
                index: nindex,
                ..
            },
        ) => clip_id == nid && index == nindex,
        (
            UpdateMask { clip_id, index, .. },
            UpdateMask {
                clip_id: nid,
                index: nindex,
                ..
            },
        ) => clip_id == nid && index == nindex,
        (
            UpdateAudioFilterDuration { clip_id, index, .. },
            UpdateAudioFilterDuration {
                clip_id: nid,
                index: nindex,
                ..
            },
        ) => clip_id == nid && index == nindex,
        _ => false,
    }
}

#[derive(Debug, Clone)]
struct OpenTransaction {
    label: String,
    forward: Vec<EditCommand>,
    inverse: Vec<EditCommand>,
}

#[derive(Debug, Clone)]
pub struct History {
    undo: Vec<Transaction>,
    redo: Vec<Transaction>,
    open: Option<OpenTransaction>,
    capacity: usize,
}

impl History {
    pub fn new(capacity: usize) -> Self {
        Self {
            undo: Vec::with_capacity(capacity),
            redo: Vec::new(),
            open: None,
            capacity,
        }
    }

    /// Open an explicit gesture transaction. Never drops a leftover open
    /// transaction: any in-flight batch is committed first so live drags and
    /// scrubs aren't lost when a new edit starts.
    pub fn begin(&mut self, label: impl Into<String>) {
        if self.open.is_some() {
            self.commit();
        }
        self.open = Some(OpenTransaction {
            label: label.into(),
            forward: Vec::new(),
            inverse: Vec::new(),
        });
    }

    /// Record one applied command and its apply-time inverse. Inside an open
    /// transaction, coalescable repeats replace the stored forward value (the
    /// oldest inverse — the pre-gesture state — is kept). Outside one, the
    /// command becomes its own single-command transaction.
    pub fn record(&mut self, forward: EditCommand, inverse: EditCommand) {
        if let Some(t) = &mut self.open {
            if let Some(last) = t.forward.last_mut() {
                if coalesce(last, &forward) {
                    *last = forward;
                    return;
                }
            }
            t.forward.push(forward);
            t.inverse.push(inverse);
        } else {
            self.push_transaction(Transaction {
                label: String::new(),
                forward: vec![forward],
                inverse: vec![inverse],
            });
        }
    }

    /// Close the open transaction and make it undoable. Consecutive
    /// transactions with the same non-empty label whose boundary commands
    /// coalesce fold into a single undo step. Unlabeled transactions never
    /// fold: only explicit gesture labels opt into cross-commit merging.
    pub fn commit(&mut self) {
        let Some(t) = self.open.take() else {
            return;
        };
        if t.forward.is_empty() {
            return;
        }
        let merge = !t.label.is_empty()
            && self.undo.last().is_some_and(|prev| {
                prev.label == t.label
                    && prev
                        .forward
                        .last()
                        .zip(t.forward.first())
                        .is_some_and(|(a, b)| {
                            let mut a = a.clone();
                            coalesce(&mut a, b)
                        })
            });
        if merge {
            let prev = self.undo.last_mut().expect("merge requires a prior entry");
            prev.forward.extend(t.forward);
        } else {
            self.push_transaction(Transaction {
                label: t.label,
                forward: t.forward,
                inverse: t.inverse,
            });
        }
    }

    /// Take the open transaction back for rollback. The caller applies the
    /// returned inverses in reverse; History itself never touches the document.
    pub fn take_open(&mut self) -> Option<(Vec<EditCommand>, Vec<EditCommand>)> {
        self.open
            .take()
            .map(|t| (t.forward, t.inverse))
            .filter(|(f, _)| !f.is_empty())
    }

    fn push_transaction(&mut self, txn: Transaction) {
        if self.undo.len() >= self.capacity {
            self.undo.remove(0);
        }
        self.undo.push(txn);
        self.redo.clear();
    }

    pub fn pop_undo(&mut self) -> Option<Transaction> {
        self.undo.pop()
    }

    pub fn push_redo(&mut self, txn: Transaction) {
        self.redo.push(txn);
    }

    pub fn pop_redo(&mut self) -> Option<Transaction> {
        self.redo.pop()
    }

    pub fn push_undo(&mut self, txn: Transaction) {
        if self.undo.len() >= self.capacity {
            self.undo.remove(0);
        }
        self.undo.push(txn);
    }

    pub fn can_undo(&self) -> bool {
        !self.undo.is_empty()
    }

    pub fn can_redo(&self) -> bool {
        !self.redo.is_empty()
    }

    /// Undo steps (transactions), not commands: a coalesced drag counts once.
    pub fn undo_depth(&self) -> usize {
        self.undo.len()
    }

    pub fn redo_depth(&self) -> usize {
        self.redo.len()
    }

    /// Label of the next undo step (for "Undo <label>" menus).
    pub fn undo_label(&self) -> Option<&str> {
        self.undo.last().map(|t| t.label.as_str())
    }

    /// Label of the next redo step.
    pub fn redo_label(&self) -> Option<&str> {
        self.redo.last().map(|t| t.label.as_str())
    }

    /// True while a gesture transaction is open (between `begin`/`commit`).
    pub fn transaction_open(&self) -> bool {
        self.open.is_some()
    }

    pub fn clear(&mut self) {
        self.undo.clear();
        self.redo.clear();
        self.open = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use miniter_domain::clip::ClipId;
    use miniter_domain::time::{MediaDuration, Timestamp};
    use miniter_domain::track::TrackId;
    use uuid::Uuid;

    fn trim_end(id: Uuid, dur_us: i64) -> EditCommand {
        EditCommand::TrimClipEnd {
            clip_id: ClipId(id),
            new_duration: MediaDuration::from_micros(dur_us),
        }
    }

    fn toggle_mute(id: Uuid, muted: bool) -> EditCommand {
        EditCommand::SetClipMuted {
            clip_id: ClipId(id),
            muted,
        }
    }

    #[test]
    fn open_drag_coalesces_keeping_oldest_inverse() {
        let mut h = History::new(50);
        let id = Uuid::new_v4();
        h.begin("Trim");
        h.record(trim_end(id, 9_000_000), trim_end(id, 10_000_000));
        h.record(trim_end(id, 8_000_000), trim_end(id, 9_000_000));
        h.record(trim_end(id, 7_000_000), trim_end(id, 8_000_000));
        h.commit();
        assert_eq!(h.undo_depth(), 1);
        let txn = h.pop_undo().unwrap();
        assert_eq!(txn.forward().len(), 1);
        assert_eq!(txn.inverse().len(), 1);
        match (&txn.forward()[0], &txn.inverse()[0]) {
            (
                EditCommand::TrimClipEnd {
                    new_duration: f, ..
                },
                EditCommand::TrimClipEnd {
                    new_duration: inv, ..
                },
            ) => {
                assert_eq!(*f, MediaDuration::from_micros(7_000_000));
                assert_eq!(*inv, MediaDuration::from_micros(10_000_000));
            }
            _ => panic!("unexpected shapes"),
        }
    }

    #[test]
    fn different_targets_do_not_coalesce_in_gesture() {
        let mut h = History::new(50);
        h.begin("Trim");
        h.record(
            trim_end(Uuid::new_v4(), 9_000_000),
            trim_end(Uuid::new_v4(), 10_000_000),
        );
        h.record(
            trim_end(Uuid::new_v4(), 8_000_000),
            trim_end(Uuid::new_v4(), 9_000_000),
        );
        h.commit();
        let txn = h.pop_undo().unwrap();
        assert_eq!(txn.forward().len(), 2);
        assert_eq!(txn.inverse().len(), 2);
    }

    #[test]
    fn label_folding_rules() {
        let mut h = History::new(50);
        let id = Uuid::new_v4();
        for dur in [9_000_000, 8_000_000, 7_000_000] {
            h.begin("Volume");
            h.record(trim_end(id, dur), trim_end(id, dur + 1_000_000));
            h.commit();
        }
        assert_eq!(h.undo_depth(), 1);
        let txn = h.pop_undo().unwrap();
        assert_eq!(txn.label, "Volume");
        assert_eq!(txn.forward().len(), 3);
        assert_eq!(txn.inverse().len(), 1);

        for dur in [9_000_000, 8_000_000] {
            h.begin("");
            h.record(trim_end(id, dur), trim_end(id, dur + 1_000_000));
            h.commit();
        }
        assert_eq!(h.undo_depth(), 2);

        h.begin("Trim");
        h.record(trim_end(id, 9_000_000), trim_end(id, 10_000_000));
        h.commit();
        h.begin("Nudge");
        h.record(trim_end(id, 8_000_000), trim_end(id, 9_000_000));
        h.commit();
        assert_eq!(h.undo_depth(), 4);

        let t = Uuid::new_v4();
        for name in ["a", "ab", "abc"] {
            h.begin("Rename");
            h.record(
                EditCommand::RenameTrack {
                    track_id: TrackId(t),
                    new_name: name.into(),
                },
                EditCommand::RenameTrack {
                    track_id: TrackId(t),
                    new_name: String::new(),
                },
            );
            h.commit();
        }
        assert_eq!(h.undo_depth(), 5);
        assert_eq!(h.undo_label(), Some("Rename"));
    }

    #[test]
    fn discrete_ops_never_coalesce() {
        let mut h = History::new(50);
        let id = Uuid::new_v4();
        h.begin("Mute");
        h.record(toggle_mute(id, true), toggle_mute(id, false));
        h.record(toggle_mute(id, false), toggle_mute(id, true));
        let batch = || EditCommand::Batch {
            label: "x".into(),
            commands: vec![],
        };
        h.record(batch(), batch());
        h.record(batch(), batch());
        h.commit();
        assert_eq!(h.pop_undo().unwrap().forward().len(), 4);
    }

    #[test]
    fn gesture_lifecycle() {
        let mut h = History::new(50);
        let id = Uuid::new_v4();
        h.begin("Trim");
        h.record(trim_end(id, 9_000_000), trim_end(id, 10_000_000));
        h.begin("Move");
        h.record(
            EditCommand::MoveClip {
                clip_id: ClipId(id),
                new_track_id: TrackId(Uuid::new_v4()),
                new_start: Timestamp::from_micros(100),
            },
            EditCommand::MoveClip {
                clip_id: ClipId(id),
                new_track_id: TrackId(Uuid::new_v4()),
                new_start: Timestamp::from_micros(0),
            },
        );
        h.commit();
        assert_eq!(h.undo_depth(), 2);
        assert_eq!(h.undo_label(), Some("Move"));

        h.begin("Trim");
        h.record(trim_end(id, 9_000_000), trim_end(id, 10_000_000));
        let (forward, inverse) = h.take_open().unwrap();
        assert_eq!(forward.len(), 1);
        assert_eq!(inverse.len(), 1);
        assert!(!h.transaction_open());

        h.begin("Trim");
        h.commit();
        assert_eq!(h.undo_depth(), 2);
    }

    #[test]
    fn capacity_evicts_oldest_transactions() {
        let mut h = History::new(2);
        for i in 0..4 {
            h.record(trim_end(Uuid::new_v4(), i), trim_end(Uuid::new_v4(), i));
        }
        assert_eq!(h.undo_depth(), 2);
    }
}
