import React, { useEffect, useRef } from 'react';
import { usePlayer } from '../../context/PlayerContext';

export const AudioVisualizer: React.FC = () => {
  const { isPlaying, dominantColor, currentTrack } = usePlayer();
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const animFrameId = useRef<number | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let phase = 0;
    const numBars = 48;

    const render = () => {
      canvas.width = canvas.parentElement?.clientWidth || 600;
      canvas.height = canvas.parentElement?.clientHeight || 300;

      const width = canvas.width;
      const height = canvas.height;
      const centerY = height / 2;

      ctx.clearRect(0, 0, width, height);

      // Draw dynamic glowing bars
      const barWidth = (width / numBars) * 0.6;
      const gap = (width / numBars) * 0.4;

      for (let i = 0; i < numBars; i++) {
        const x = i * (barWidth + gap) + gap / 2;
        
        // Compute pseudo-audio amplitude wave
        let amplitude = 10;
        if (isPlaying) {
          const wave1 = Math.sin(phase + i * 0.25);
          const wave2 = Math.cos(phase * 1.5 + i * 0.15);
          const wave3 = Math.sin(phase * 0.8 + i * 0.05);
          const rawAmp = (Math.abs(wave1 * 0.5 + wave2 * 0.3 + wave3 * 0.2) + 0.1) * (height * 0.38);
          amplitude = Math.max(8, rawAmp);
        } else {
          amplitude = 6;
        }

        const gradient = ctx.createLinearGradient(0, centerY - amplitude, 0, centerY + amplitude);
        gradient.addColorStop(0, 'rgba(255, 255, 255, 0.9)');
        gradient.addColorStop(0.3, dominantColor);
        gradient.addColorStop(1, 'rgba(147, 51, 234, 0.4)');

        ctx.fillStyle = gradient;
        ctx.shadowColor = dominantColor;
        ctx.shadowBlur = isPlaying ? 16 : 4;

        // Rounded bar
        const radius = barWidth / 2;
        ctx.beginPath();
        ctx.roundRect(x, centerY - amplitude, barWidth, amplitude * 2, radius);
        ctx.fill();
      }

      if (isPlaying) {
        phase += 0.06;
      }

      animFrameId.current = requestAnimationFrame(render);
    };

    render();

    return () => {
      if (animFrameId.current) cancelAnimationFrame(animFrameId.current);
    };
  }, [isPlaying, dominantColor, currentTrack]);

  return (
    <div className="w-full h-full flex flex-col items-center justify-center p-6 text-[var(--text-primary)]">
      <div className="w-full max-w-3xl h-64 relative flex items-center justify-center">
        <canvas ref={canvasRef} className="w-full h-full" />
      </div>
      <div className="text-center mt-4 space-y-1">
        <p className="text-sm font-semibold text-[var(--text-primary)]">Ambient Motion</p>
        {/*
          Truthful caption. These bars are generated from sine waves and the play/pause
          state — they are NOT frequency analysis of the audio. Playback runs through a
          cross-origin YouTube IFrame player, so no MediaElementAudioSourceNode can be
          attached and real spectrum data is not available to this app.
        */}
        <p className="text-xs text-[var(--text-muted)]">
          Decorative animation driven by playback state, not audio analysis
        </p>
      </div>
    </div>
  );
};
