import React from 'react';
import { Home, Search, Heart, Library } from 'lucide-react';

interface MobileNavProps {
  activeView: string;
  setActiveView: (view: string) => void;
}

export const MobileNav: React.FC<MobileNavProps> = ({ activeView, setActiveView }) => {
  // Liked is a real view (FavoritesView) that the desktop sidebar links to, but
  // this bar omitted it, so on a phone there was no way to reach saved songs.
  const navItems = [
    { id: 'home', label: 'Home', icon: Home },
    { id: 'explore', label: 'Search', icon: Search },
    { id: 'library', label: 'Library', icon: Library },
    { id: 'favorites', label: 'Liked', icon: Heart },
  ];

  return (
    <nav className="md:hidden fixed bottom-0 left-0 right-0 z-30 bg-[#0e100c] border-t border-[#1e2316] px-2 py-1.5 flex items-center justify-around select-none">
      {navItems.map((item) => {
        const Icon = item.icon;
        const isActive = activeView === item.id || (item.id === 'explore' && activeView === 'search');
        return (
          <button
            key={item.id}
            onClick={() => setActiveView(item.id)}
            className="flex flex-col items-center gap-1 py-1 px-2 transition"
          >
            <div
              className={`flex items-center justify-center px-4 py-1 rounded-full transition-all duration-200 ${
                isActive ? 'bg-[#3c472a] text-[#dbe7b5]' : 'text-[#8f9b75] hover:text-[#dbe7b5]'
              }`}
            >
              <Icon className="w-5 h-5" />
            </div>
            <span
              className={`text-[11px] tracking-wide ${
                isActive ? 'font-bold text-[#e1e9cc]' : 'font-medium text-[#8f9b75]'
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
