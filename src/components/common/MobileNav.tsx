import React from 'react';
import { Home, Search, Library } from 'lucide-react';

interface MobileNavProps {
  activeView: string;
  setActiveView: (view: string) => void;
}

export const MobileNav: React.FC<MobileNavProps> = ({ activeView, setActiveView }) => {
  const navItems = [
    { id: 'home', label: 'Home', icon: Home },
    { id: 'explore', label: 'Search', icon: Search },
    { id: 'library', label: 'Library', icon: Library },
  ];

  return (
    <nav className="md:hidden fixed bottom-0 left-0 right-0 z-30 bg-[var(--bg-nav)] border-t border-[var(--m3-outline-variant)] backdrop-blur-xl px-[max(0.5rem,env(safe-area-inset-left,0px))] pr-[max(0.5rem,env(safe-area-inset-right,0px))] pt-1.5 pb-[max(0.375rem,env(safe-area-inset-bottom,0px))] flex items-center justify-around select-none transition-colors duration-200">
      {navItems.map((item) => {
        const Icon = item.icon;
        const isActive = activeView === item.id || (item.id === 'explore' && activeView === 'search');
        return (
          <button
            key={item.id}
            onClick={() => setActiveView(item.id)}
            className="flex flex-col items-center gap-1 py-1 px-2 transition cursor-pointer"
            aria-current={isActive ? 'page' : undefined}
          >
            {/* Material 3 navigation bar: the selected destination is marked by a
                filled tonal pill behind the icon, not by an accent-coloured icon
                on its own. The pill keeps the same 4px/16px geometry it had, so
                the bar's measured height is unchanged. */}
            <div
              className={`flex items-center justify-center px-4 py-1 rounded-full transition-all duration-200 ${
                isActive
                  ? 'bg-[var(--m3-secondary-container)] text-[var(--m3-on-secondary-container)]'
                  : 'text-[var(--text-muted)] hover:text-[var(--text-primary)] active:bg-[var(--m3-primary-08)]'
              }`}
            >
              <Icon className="w-5 h-5" />
            </div>
            <span
              className={`text-[11px] tracking-wide ${
                isActive ? 'font-bold text-[var(--text-primary)]' : 'font-medium text-[var(--text-muted)]'
              }`}
            >
              {item.label}
            </span>
          </button>
        );
      })}
    </nav>
  );
};
