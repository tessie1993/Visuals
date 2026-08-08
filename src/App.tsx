import React, { useRef, useState, useEffect } from 'react';
import { Mic, Square, Play } from 'lucide-react';

export default function App() {
  const [isListening, setIsListening] = useState(false);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const sourceRef = useRef<MediaStreamAudioSourceNode | null>(null);
  const requestRef = useRef<number>();

  const startListening = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      audioContextRef.current = new (window.AudioContext || (window as any).webkitAudioContext)();
      analyserRef.current = audioContextRef.current.createAnalyser();
      sourceRef.current = audioContextRef.current.createMediaStreamSource(stream);
      
      sourceRef.current.connect(analyserRef.current);
      analyserRef.current.fftSize = 256;
      
      setIsListening(true);
      draw();
    } catch (err) {
      console.error('Error accessing microphone:', err);
      alert('Could not access microphone. Please ensure permissions are granted.');
    }
  };

  const stopListening = () => {
    if (sourceRef.current) {
      sourceRef.current.mediaStream.getTracks().forEach(track => track.stop());
    }
    if (audioContextRef.current) {
      audioContextRef.current.close();
    }
    if (requestRef.current) {
      cancelAnimationFrame(requestRef.current);
    }
    setIsListening(false);
  };

  const draw = () => {
    if (!canvasRef.current || !analyserRef.current) return;
    
    const canvas = canvasRef.current;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    
    const bufferLength = analyserRef.current.frequencyBinCount;
    const dataArray = new Uint8Array(bufferLength);
    
    const render = () => {
      requestRef.current = requestAnimationFrame(render);
      analyserRef.current!.getByteFrequencyData(dataArray);
      
      ctx.fillStyle = '#0f172a';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      
      const barWidth = (canvas.width / bufferLength) * 2.5;
      let barHeight;
      let x = 0;
      
      for (let i = 0; i < bufferLength; i++) {
        barHeight = dataArray[i] * 1.5;
        
        const r = barHeight + (25 * (i/bufferLength));
        const g = 250 * (i/bufferLength);
        const b = 50;
        
        ctx.fillStyle = `rgb(${r},${g},${b})`;
        ctx.fillRect(x, canvas.height - barHeight, barWidth, barHeight);
        
        x += barWidth + 1;
      }
    };
    
    render();
  };

  useEffect(() => {
    const handleResize = () => {
      if (canvasRef.current) {
        canvasRef.current.width = window.innerWidth;
        canvasRef.current.height = window.innerHeight;
      }
    };
    
    window.addEventListener('resize', handleResize);
    handleResize(); // Initial resize
    
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  useEffect(() => {
    return () => {
      stopListening();
    };
  }, []);

  return (
    <div className="relative w-full h-screen bg-slate-900 overflow-hidden flex flex-col items-center justify-center">
      <canvas 
        ref={canvasRef} 
        className="absolute inset-0 w-full h-full"
      />
      
      <div className="z-10 bg-slate-800/80 backdrop-blur p-6 rounded-2xl shadow-xl border border-slate-700 max-w-sm w-full mx-4 text-center">
        <h1 className="text-2xl font-bold text-white mb-2">Music Visualizer</h1>
        <p className="text-slate-400 mb-8 text-sm">
          A web-based port of the Android visualizer. Allows microphone input to drive the visualization.
        </p>
        
        {!isListening ? (
          <button 
            onClick={startListening}
            className="w-full flex items-center justify-center gap-2 bg-indigo-600 hover:bg-indigo-700 text-white py-3 px-6 rounded-xl font-medium transition-colors"
          >
            <Mic size={20} />
            Start Listening
          </button>
        ) : (
          <button 
            onClick={stopListening}
            className="w-full flex items-center justify-center gap-2 bg-rose-600 hover:bg-rose-700 text-white py-3 px-6 rounded-xl font-medium transition-colors"
          >
            <Square size={20} />
            Stop
          </button>
        )}
      </div>
    </div>
  );
}
