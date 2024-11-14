import { useState, useEffect, createElement } from "react";
import { invoke } from "@tauri-apps/api/core";
import { emit, listen } from "@tauri-apps/api/event";
import "./App.css";

const resizeHandler = () => { invoke('on_resize', {}); };

function App() {
  const [renderData, setRenderData] = useState({});

  const setupRenderListener = () => {
    document.querySelector(':root').style.opacity = '1';
    const unlistenPromise = listen('render', (event) => {
      const { string_hash, render_string, is_first_render } = event.payload;
      const markup = JSON.parse(render_string);
      setRenderData({ markup, hash: string_hash });
    }).then((unlisten) => {
      emit('ready', {});
      return unlisten;
    });
    return () => {
      unlistenPromise.then((unlisten) => unlisten());
    };
  };
  useEffect(setupRenderListener, []);

  const setupResizeListener = () => {
    window.addEventListener('resize', resizeHandler);
    return () => { window.removeEventListener('resize', resizeHandler); };
  };
  useEffect(setupResizeListener, []);

  const hasRenderData = !!renderData.markup;
  const markup = renderData.markup || '';
  const hash = renderData.hash;

  useEffect(() => {
    if (!hasRenderData) { return; }
    const content = document.getElementById("content");
    const width = content.clientWidth;
    const height = content.clientHeight;
    invoke('post_render', { width, height, hash });
    return;
  });

  const toEl = (chunk) => {
    if (!Array.isArray(chunk)) { return chunk; }
    const [tagSpec, ...childrenChunks] = chunk;
    const [tag, ...classParts] = tagSpec.split(".");
    const className = classParts.join(" ");
    const children = childrenChunks.map(toEl);
    const props = children.length ? { className, children } : { className };
    return createElement(tag, props);
  };

  return (
    <main className="container">
      <div id="content"> {toEl(markup)} </div>
    </main>
  );
}

export default App;
