import React, { useState } from 'react';
import { usePlayer } from '../../context/PlayerContext';
import { Plus, X, ListMusic } from 'lucide-react';


interface CreatePlaylistModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const CreatePlaylistModal: React.FC<CreatePlaylistModalProps> = ({ isOpen, onClose }) => {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const { createPlaylist } = usePlayer();

  if (!isOpen) return null;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!title.trim()) return;

    createPlaylist(title.trim(), description.trim());
    setTitle('');
    setDescription('');

    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-in fade-in">
      <div className="relative w-full max-w-md rounded-3xl bg-neutral-900 border border-white/10 p-6 shadow-2xl space-y-6">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-2xl bg-purple-500/10 text-purple-400 border border-purple-500/20">
              <ListMusic className="w-5 h-5" />
            </div>
            <h3 className="font-display font-black text-xl text-white">Create New Playlist</h3>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-full hover:bg-white/10 text-neutral-400 hover:text-white transition"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-1.5">
            <label className="text-xs font-bold uppercase tracking-wider text-neutral-400">
              Playlist Title
            </label>
            <input
              type="text"
              required
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="e.g. Late Night Lo-Fi or Gym Motivation"
              className="w-full px-4 py-3 bg-neutral-800/80 rounded-2xl border border-white/10 text-sm text-white placeholder-neutral-500 focus:border-purple-500 focus:outline-none"
              autoFocus
            />
          </div>

          <div className="space-y-1.5">
            <label className="text-xs font-bold uppercase tracking-wider text-neutral-400">
              Description (Optional)
            </label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Add a vibe or description for your playlist..."
              rows={3}
              className="w-full px-4 py-3 bg-neutral-800/80 rounded-2xl border border-white/10 text-sm text-white placeholder-neutral-500 focus:border-purple-500 focus:outline-none resize-none"
            />
          </div>

          <div className="flex items-center justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-5 py-2.5 rounded-full bg-white/5 hover:bg-white/10 text-xs font-semibold text-neutral-300 transition"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="px-6 py-2.5 rounded-full bg-purple-600 hover:bg-purple-500 text-xs font-bold text-white shadow-lg transition"
            >
              Create Playlist
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
