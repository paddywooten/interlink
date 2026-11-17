/** Shown while files are being dragged into the app window. The desktop build
 *  gets real paths from Tauri; the browser gets File objects from HTML5 drop. */
export default function DropOverlay({ active }: { active: boolean }) {
  if (!active) return null;
  return (
    <div className="drop-overlay">
      <div className="drop-card">
        <div className="drop-icon">⬇</div>
        <b>Drop files to copy them to your phone</b>
        <span>They will land in Downloads/InterLink on the phone.</span>
      </div>
    </div>
  );
}
