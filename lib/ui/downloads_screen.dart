import 'dart:async';
import 'dart:io';

import 'package:filesize/filesize.dart';
import 'package:flutter/material.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../api/download.dart';
import '../translations.i18n.dart';
import 'elements.dart';
import 'cached_image.dart';

class DownloadsScreen extends StatefulWidget {
  const DownloadsScreen({super.key});

  @override
  _DownloadsScreenState createState() => _DownloadsScreenState();
}

class _DownloadsScreenState extends State<DownloadsScreen> {
  List<Download> downloads = [];
  StreamSubscription? _stateSubscription;

  //Sublists, split in one pass
  List<Download> downloading = [];
  List<Download> queued = [];
  List<Download> failed = [];
  List<Download> finished = [];

  void _split() {
    downloading = [];
    queued = [];
    failed = [];
    finished = [];
    for (Download d in downloads) {
      switch (d.state) {
        case DownloadState.DOWNLOADING:
        case DownloadState.POST:
          downloading.add(d);
          break;
        case DownloadState.NONE:
          queued.add(d);
          break;
        case DownloadState.ERROR:
        case DownloadState.DEEZER_ERROR:
          failed.add(d);
          break;
        case DownloadState.DONE:
          finished.add(d);
          break;
        default:
          break;
      }
    }
  }

  Future _load() async {
    //Load downloads
    List<Download> d = await downloadManager.getDownloads();
    if (!mounted) return;
    setState(() {
      downloads = d;
      _split();
    });
  }

  //Remove from the list right away, the service removes them asynchronously,
  //reloading immediately could still return them (and reloading a long list is slow)
  void _removeLocal(bool Function(Download d) test) {
    setState(() {
      downloads.removeWhere(test);
      _split();
    });
  }

  Future _removeByStates(List<DownloadState> states) async {
    _removeLocal((d) => states.contains(d.state));
    for (DownloadState state in states) {
      await downloadManager.removeDownloads(state);
    }
  }

  @override
  void initState() {
    _load();

    //Subscribe to state update
    _stateSubscription = downloadManager.serviceEvents.stream.listen((e) {
      //State change = update
      if (e['action'] == 'onStateChange') {
        setState(() => downloadManager.running = downloadManager.running);
      }
      //Progress change
      if (e['action'] == 'onProgress') {
        setState(() {
          for (Map su in e['data']) {
            downloads.firstWhere((d) => d.id == su['id'], orElse: () => Download()).updateFromJson(su);
          }
          _split();
        });
      }
    });

    super.initState();
  }

  @override
  void dispose() {
    _stateSubscription?.cancel();
    _stateSubscription = null;
    super.dispose();
  }

  Widget _header(String text) => Text(
        text,
        textAlign: TextAlign.center,
        style: const TextStyle(fontSize: 24.0, fontWeight: FontWeight.bold),
      );

  Widget _tile(Download d) => DownloadTile(
        d,
        key: ValueKey(d.id),
        updateCallback: () => _removeLocal((x) => x.id == d.id),
      );

  @override
  Widget build(BuildContext context) {
    //Flat list of item builders, ListView.builder only builds the visible ones
    List<Widget Function()> items = [
      () => Container(height: 2.0),
      for (Download d in downloading) () => _tile(d),
      () => Container(height: 8.0),

      //Queued
      if (queued.isNotEmpty) () => _header('Queued'.i18n),
      for (Download d in queued) () => _tile(d),
      if (queued.isNotEmpty)
        () => ListTile(
              title: Text('Clear queue'.i18n),
              leading: const Icon(Icons.delete),
              onTap: () => _removeByStates([DownloadState.NONE]),
            ),

      //Failed
      if (failed.isNotEmpty) () => _header('Failed'.i18n),
      for (Download d in failed) () => _tile(d),
      //Restart failed
      if (failed.isNotEmpty)
        () => ListTile(
              title: Text('Restart failed downloads'.i18n),
              leading: const Icon(Icons.restore),
              onTap: () async {
                await downloadManager.retryDownloads();
                await _load();
              },
            ),
      if (failed.isNotEmpty)
        () => ListTile(
              title: Text('Clear failed'.i18n),
              leading: const Icon(Icons.delete),
              onTap: () => _removeByStates([DownloadState.ERROR, DownloadState.DEEZER_ERROR]),
            ),

      //Finished
      if (finished.isNotEmpty) () => _header('Done'.i18n),
      for (Download d in finished) () => _tile(d),
      if (finished.isNotEmpty)
        () => ListTile(
              title: Text('Clear downloads history'.i18n),
              leading: const Icon(Icons.delete),
              onTap: () => _removeByStates([DownloadState.DONE]),
            ),
    ];

    return Scaffold(
        appBar: FreezerAppBar(
          'Downloads'.i18n,
          actions: [
            IconButton(
              icon: Icon(
                Icons.delete_sweep,
                semanticLabel: 'Clear all'.i18n,
              ),
              onPressed: () => _removeByStates(
                  [DownloadState.ERROR, DownloadState.DEEZER_ERROR, DownloadState.DONE, DownloadState.NONE]),
            ),
            IconButton(
              icon: Icon(
                downloadManager.running ? Icons.stop : Icons.play_arrow,
                semanticLabel: downloadManager.running ? 'Stop'.i18n : 'Start'.i18n,
              ),
              onPressed: () {
                setState(() {
                  if (downloadManager.running) {
                    downloadManager.stop();
                  } else {
                    downloadManager.start();
                  }
                });
              },
            )
          ],
        ),
        body: ListView.builder(
          itemCount: items.length,
          itemBuilder: (context, i) => items[i](),
        ));
  }
}

class DownloadTile extends StatelessWidget {
  final Download download;
  final Function updateCallback;
  const DownloadTile(this.download, {super.key, required this.updateCallback});

  String subtitle() {
    String out = '';

    if (download.state != DownloadState.DOWNLOADING && download.state != DownloadState.POST) {
      //Download type
      if (download.private ?? false) {
        out += 'Offline'.i18n;
      } else {
        out += 'External'.i18n;
      }
      out += ' | ';
    }

    if (download.state == DownloadState.POST) {
      return 'Post processing...'.i18n;
    }

    //Quality
    if (download.quality == 9) out += 'FLAC';
    if (download.quality == 3) out += 'MP3 320kbps';
    if (download.quality == 1) out += 'MP3 128kbps';

    //Downloading show progress
    if (download.state == DownloadState.DOWNLOADING) {
      out += ' | ${filesize(download.received, 2)} / ${filesize(download.filesize, 2)}';
      double progress = download.received!.toDouble() / download.filesize!.toDouble();
      out += ' ${(progress * 100.0).toStringAsFixed(2)}%';
    }

    return out;
  }

  Future onClick(BuildContext context) async {
    if (download.state != DownloadState.DOWNLOADING && download.state != DownloadState.POST) {
      showDialog(
          context: context,
          builder: (context) {
            return AlertDialog(
              title: Text('Delete'.i18n),
              content: Text('Are you sure you want to delete this download?'.i18n),
              actions: [
                TextButton(
                  child: Text('Cancel'.i18n),
                  onPressed: () => Navigator.of(context).pop(),
                ),
                TextButton(
                  child: Text('Delete'.i18n),
                  onPressed: () async {
                    await downloadManager.removeDownload(download.id!);
                    updateCallback();
                    if (context.mounted) Navigator.of(context).pop();
                  },
                )
              ],
            );
          });
    }
  }

  //Trailing icon with state
  Widget trailing() {
    switch (download.state) {
      case DownloadState.NONE:
        return const Icon(
          Icons.query_builder,
        );
      case DownloadState.DOWNLOADING:
        return const Icon(Icons.download_rounded);
      case DownloadState.POST:
        return const Icon(Icons.miscellaneous_services);
      case DownloadState.DONE:
        return const Icon(
          Icons.done,
          color: Colors.green,
        );
      case DownloadState.DEEZER_ERROR:
        return const Icon(Icons.error, color: Colors.blue);
      case DownloadState.ERROR:
        return const Icon(Icons.error, color: Colors.red);
      default:
        return Container();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        ListTile(
          title: Text(download.title!),
          leading: CachedImage(url: download.image!, width: 48),
          subtitle: Text(subtitle(), maxLines: 1, overflow: TextOverflow.ellipsis),
          trailing: trailing(),
          onTap: () => onClick(context),
        ),
        if (download.state == DownloadState.DOWNLOADING) LinearProgressIndicator(value: download.progress),
        if (download.state == DownloadState.POST) const LinearProgressIndicator(),
      ],
    );
  }
}

class DownloadLogViewer extends StatefulWidget {
  const DownloadLogViewer({super.key});

  @override
  _DownloadLogViewerState createState() => _DownloadLogViewerState();
}

class _DownloadLogViewerState extends State<DownloadLogViewer> {
  List<String> data = [];

  //Load log from file
  Future _load() async {
    String path = p.join((await getExternalStorageDirectory())!.path, 'download.log');
    File file = File(path);
    if (await file.exists()) {
      String d = await file.readAsString();
      setState(() {
        data = d.replaceAll('\r', '').split('\n');
      });
    }
  }

  //Get color by log type
  Color? color(String line) {
    if (line.startsWith('E:')) return Colors.red;
    if (line.startsWith('W:')) return Colors.orange[600];
    return null;
  }

  @override
  void initState() {
    _load();
    super.initState();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
        appBar: FreezerAppBar('Download Log'.i18n),
        body: ListView.builder(
          itemCount: data.length,
          itemBuilder: (context, i) {
            return Padding(
              padding: const EdgeInsets.all(8.0),
              child: Text(
                data[i],
                style: TextStyle(fontSize: 14.0, color: color(data[i])),
              ),
            );
          },
        ));
  }
}
