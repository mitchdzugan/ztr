// const windowConfig = { frame: false, transparent: true };
const windowConfig = {  };
nw.Window.open('index.html', windowConfig, function(win) {
    console.log(win);
});

