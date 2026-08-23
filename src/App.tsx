import React, { useState, useEffect } from 'react';
import { AuthProvider } from './context/AuthContext';
import { PlayerProvider, usePlayer } from './context/PlayerContext';
import { ListenTogetherProvider, useListenTogether } from './context/ListenTogetherContext';
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
import { ListenTogetherModal } from './components/modals/ListenTogetherModal';
import { ToastContainer } from './components/common/Toast';
import { extractRoomCodeFromUrl } from './lib/listenTogether';
import type { Track } from './types/music';

const AppContent: React.FC = () => {
  const [activeView, setActiveView] = useState<string>('home');
  // The query Explore should run, plus a nonce so submitting the *same* query
  // again still re-triggers the search instead of being ignored as unchanged.
  const [exploreRequest, setExploreRequest] = useState<{ query: string; nonce: number }>({
    query: '',
    nonce: 0,
  });
  const [isCreatePlaylistOpen, setIsCreatePlaylistOpen] = useState<boolean>(false);
  // A playlist the Library should open as soon as it mounts, set when one is
  // clicked in the sidebar. Cleared by the Library once consumed, so switching
  // away and back does not reopen it.
  const [pendingPlaylistId, setPendingPlaylistId] = useState<string | undefined>(undefined);
  const { dominantColor } = usePlayer();
  const { setInviteCodeToOpen, setIsModalOpen } = useListenTogether();

  // Check URL on startup for ?room=CODE invite links
  useEffect(() => {
    const code = extractRoomCodeFromUrl(window.location.href);
    if (code) {
      setInviteCodeToOpen(code);
      setIsModalOpen(true);
      // Clean up room code from query params so page refresh is clean
      try {
        const url = new URL(window.location.href);
        url.searchParams.delete('room');
        window.history.replaceState({}, document.title, url.pathname + url.search + url.hash);
      } catch {}
    }
  }, [setInviteCodeToOpen, setIsModalOpen]);

  /** Send a query to Explore and switch to it. Used by genre tiles and header search. */
  const handleSelectGenre = (genreQuery: string) => {
    setExploreRequest((prev) => ({ query: genreQuery, nonce: prev.nonce + 1 }));
    setActiveView('explore');
  };

  /** Open a specific playlist. The sidebar previously just switched to the
   *  Library and dropped the id, so every playlist link led to the same grid. */
  const handleOpenPlaylist = (playlistId: string) => {
    setPendingPlaylistId(playlistId);
    setActiveView('library');
  };

  const handleSearchSelect = (_track: Track) => {
    // Song is played automatically by Header
  };

  return (
    <div className="relative flex h-[100dvh] w-screen bg-[var(--bg-base)] text-[var(--text-primary)] overflow-hidden font-sans transition-colors duration-200">
      {/* Background Ambient Glow Tint based on current album artwork */}
      <div className="absolute inset-0 pointer-events-none overflow-hidden opacity-10 dark:opacity-15 transition-all duration-1000">
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
        openPlaylist={handleOpenPlaylist}
      />

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col min-w-0 h-full overflow-hidden relative z-10">
        <Header
          activeView={activeView}
          setActiveView={setActiveView}
          onSearchSelect={handleSearchSelect}
          onSubmitSearch={handleSelectGenre}
        />

        <main className="flex-1 overflow-y-auto px-4 sm:px-8 py-4 pb-44 md:pb-24">
          {activeView === 'home' && (
            <HomeView onSelectGenre={handleSelectGenre} setActiveView={setActiveView} />
          )}

          {activeView === 'explore' && (
            <ExploreView
              initialQuery={exploreRequest.query}
              queryNonce={exploreRequest.nonce}
            />
          )}

          {activeView === 'library' && (
            <LibraryView
              openCreatePlaylistModal={() => setIsCreatePlaylistOpen(true)}
              openPlaylistId={pendingPlaylistId}
              onPlaylistOpened={() => setPendingPlaylistId(undefined)}
              onSelectArtist={handleSelectGenre}
            />
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

      {/* Listen Together Modal */}
      <ListenTogetherModal />

      {/* Toast Notifications */}
      <ToastContainer />
    </div>
  );
};

export default function App() {
  return (
    <AuthProvider>
      <PlayerProvider>
        <ListenTogetherProvider>
          <AppContent />
        </ListenTogetherProvider>
      </PlayerProvider>
    </AuthProvider>
  );
}
