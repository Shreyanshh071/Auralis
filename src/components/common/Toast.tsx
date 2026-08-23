import React from 'react';
import { usePlayer, type ToastMessage } from '../../context/PlayerContext';
import { CheckCircle, Info, AlertCircle, X } from 'lucide-react';

export const ToastContainer: React.FC = () => {
  const { toasts } = usePlayer();

  if (toasts.length === 0) return null;

  const iconMap: Record<ToastMessage['type'], React.ReactNode> = {
    success: <CheckCircle className="w-4 h-4 text-emerald-500 flex-shrink-0" />,
    info: <Info className="w-4 h-4 text-[var(--m3-primary)] flex-shrink-0" />,
    error: <AlertCircle className="w-4 h-4 text-rose-500 flex-shrink-0" />,
  };

  return (
    <div className="fixed bottom-[var(--toast-bottom)] left-1/2 -translate-x-1/2 z-[70] flex flex-col items-center gap-2 pointer-events-none max-w-[90vw]">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className="pointer-events-auto flex items-center gap-2.5 px-5 py-2.5 sm:py-3 rounded-full bg-[var(--bg-player-pill)] backdrop-blur-2xl border border-[var(--border-medium)]/80 dark:border-white/10 ring-1 ring-white/10 dark:ring-white/5 shadow-[0_16px_40px_rgba(0,0,0,0.35)] text-xs sm:text-sm font-semibold text-[var(--text-primary)] animate-in fade-in zoom-in-95 slide-in-from-bottom-4 duration-300 ease-out select-none"
        >
          {iconMap[toast.type]}
          <span className="whitespace-nowrap truncate">{toast.text}</span>
        </div>
      ))}
    </div>
  );
};
