import React, { useState } from 'react';
import { PlayerProvider, usePlayer } from './context/PlayerContext';
import { Sidebar } from './components/common/Sidebar';
import { Header } from './components/common/Header';
import { HomeView } from './components/views/HomeView';
import { ExploreView } from './components/views/ExploreView';
import { LibraryView } from './components/views/LibraryView';
import { FavoritesView } from './components/views/FavoritesView';
import { MiniPlayer } from './components/player/MiniPlayer';
import { NowPlayingModal } from './components/player/NowPlayingModal';
import { MobileNav } from './components/common/MobileNav';
import { CreatePlaylistModal } from './components/modals/CreatePlaylistModal';
import { ToastContainer } from './components/common/Toast';
import type { Track } from './types/music';

const AppContent: React.FC = () => {
  const [activeView, setActiveView] = useState<string>('home');
  const [searchGenreQuery, setSearchGenreQuery] = useState<string>('');
  const [isCreatePlaylistOpen, setIsCreatePlaylistOpen] = useState<boolean>(false);
  const { dominantColor } = usePlayer();

  const handleSelectGenre = (genreQuery: string) => {
    setSearchGenreQuery(genreQuery);
    setActiveView('explore');
  };

  const handleSearchSelect = (track: Track) => {
    // Song is played automatically by Header
  };

  return (
    <div className="relative flex h-screen w-screen bg-[#09090b] text-neutral-100 overflow-hidden font-sans">
      {/* Background Ambient Glow Tint based on current album artwork */}
      <div className="absolute inset-0 pointer-events-none overflow-hidden opacity-15 transition-all duration-1000">
        <div
          className="absolute -top-[10%] -left-[10%] w-[50vw] h-[50vw] rounded-full blur-[140px] transition-colors duration-1000"
          style={{ background: dominantColor }}
        />
      </div>

      {/* Desktop Sidebar (Hidden on mobile) */}
      <Sidebar
        activeView={activeView}
        setActiveView={setActiveView}
        openCreatePlaylistModal={() => setIsCreatePlaylistOpen(true)}
      />

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col min-w-0 h-full overflow-hidden relative z-10">
        <Header
          activeView={activeView}
          setActiveView={setActiveView}
          onSearchSelect={handleSearchSelect}
        />

        <main className="flex-1 overflow-y-auto px-4 sm:px-8 py-4 pb-44 md:pb-24">
          {activeView === 'home' && (
            <HomeView onSelectGenre={handleSelectGenre} setActiveView={setActiveView} />
          )}

          {activeView === 'explore' && (
            <ExploreView initialQuery={searchGenreQuery} />
          )}

          {activeView === 'library' && (
            <LibraryView openCreatePlaylistModal={() => setIsCreatePlaylistOpen(true)} />
          )}

          {activeView === 'favorites' && <FavoritesView />}
        </main>

        {/* Floating Mini Player */}
        <MiniPlayer />

        {/* Mobile Bottom Navigation Bar (Hidden on desktop) */}
        <MobileNav activeView={activeView} setActiveView={setActiveView} />
      </div>

      {/* Fullscreen Now Playing Modal */}
      <NowPlayingModal />

      {/* Create Playlist Modal */}
      <CreatePlaylistModal
        isOpen={isCreatePlaylistOpen}
        onClose={() => setIsCreatePlaylistOpen(false)}
      />

      {/* Toast Notifications */}
      <ToastContainer />
    </div>
  );
};


export default function App() {
  return (
    <PlayerProvider>
      <AppContent />
    </PlayerProvider>
  );
}
