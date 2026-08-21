import React from 'react';
import { usePlayer, type ToastMessage } from '../../context/PlayerContext';
import { CheckCircle, Info, AlertCircle, X } from 'lucide-react';

export const ToastContainer: React.FC = () => {
  const { toasts } = usePlayer();

  if (toasts.length === 0) return null;

  const iconMap: Record<ToastMessage['type'], React.ReactNode> = {
    success: <CheckCircle className="w-4 h-4 text-emerald-400 flex-shrink-0" />,
    info: <Info className="w-4 h-4 text-[#dbe7b5] flex-shrink-0" />,
    error: <AlertCircle className="w-4 h-4 text-rose-400 flex-shrink-0" />,
  };

  return (
    <div className="fixed bottom-20 md:bottom-24 left-1/2 -translate-x-1/2 z-[60] flex flex-col items-center gap-2 pointer-events-none">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className="pointer-events-auto flex items-center gap-2 px-4 py-2.5 rounded-xl bg-[#1a1e14]/95 backdrop-blur-md border border-[#2b3420] shadow-2xl text-xs font-medium text-[#e8efd4] animate-in fade-in slide-in-from-bottom-3 duration-200"
        >
          {iconMap[toast.type]}
          <span>{toast.text}</span>
        </div>
      ))}
    </div>
  );
};
