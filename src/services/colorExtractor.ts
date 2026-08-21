/**
 * Extract dominant RGB color from an image URL using an offscreen canvas
 */
export async function getDominantColor(imageUrl: string): Promise<string> {
  return new Promise((resolve) => {
    const img = new Image();
    img.crossOrigin = 'Anonymous';
    img.src = imageUrl;

    img.onload = () => {
      try {
        const canvas = document.createElement('canvas');
        const ctx = canvas.getContext('2d');
        if (!ctx) {
          resolve('#6366f1'); // Default indigo
          return;
        }

        canvas.width = 40;
        canvas.height = 40;
        ctx.drawImage(img, 0, 0, 40, 40);

        const imageData = ctx.getImageData(0, 0, 40, 40).data;
        let r = 0, g = 0, b = 0;
        let count = 0;

        for (let i = 0; i < imageData.length; i += 16) {
          const red = imageData[i];
          const green = imageData[i + 1];
          const blue = imageData[i + 2];
          
          // Avoid overly dark or purely white pixels to preserve vibrancy
          const brightness = (red * 299 + green * 587 + blue * 114) / 1000;
          if (brightness > 30 && brightness < 220) {
            r += red;
            g += green;
            b += blue;
            count++;
          }
        }

        if (count === 0) {
          resolve('#7c3aed');
          return;
        }

        r = Math.floor(r / count);
        g = Math.floor(g / count);
        b = Math.floor(b / count);

        // Boost saturation slightly
        resolve(`rgb(${r}, ${g}, ${b})`);
      } catch {
        resolve('#7c3aed');
      }
    };

    img.onerror = () => {
      resolve('#7c3aed');
    };
  });
}
