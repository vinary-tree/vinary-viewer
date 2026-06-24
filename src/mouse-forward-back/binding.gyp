{
  "targets": [
    {
      "target_name": "mouse-forward-back",
      "include_dirs": [
        "<!(node -p \"require('path').dirname(require.resolve('nan/package.json'))\")"
      ],
      "libraries": [ "-lX11" ],
      "sources": [ "mouse-forward-back.cc" ]
    }
  ]
}
