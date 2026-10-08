using System;
using System.IO;
using GLTFast;
using UnityEngine;

// Loads a room scan (.glb from room_mapping/export_unity.py) at runtime from a local path, so scan data never lives in
// the Unity project or the repo. Path: the "-scan <path>" command-line argument, else the Scan Path field.
// Adds a MeshCollider so the player can walk on the floor, then drops the player into the middle of the room.
public class ScanLoader : MonoBehaviour
{
    [Tooltip("Full path to the .glb, e.g. ~/SOCOMIgnite2026/room_mapping/output/unity/007_..._lc_mesh.glb")]
    public string scanPath;

    [Tooltip("Kept inactive until the scan has loaded (so it doesn't fall through empty space), then placed in the room")]
    public GameObject player;

    async void Start()
    {
        if (player != null) player.SetActive(false);
        string path = CommandLinePath() ?? scanPath;
        if (path != null && path.StartsWith("~"))
            path = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile) + path.Substring(1);
        if (string.IsNullOrEmpty(path) || !File.Exists(path))
        {
            Debug.LogError($"Scan not found: '{path}'. Set Scan Path on ScanLoader or run with -scan <path>.");
            return;
        }

        var gltf = new GltfImport();
        if (!await gltf.Load(new Uri(Path.GetFullPath(path)).AbsoluteUri) || !await gltf.InstantiateMainSceneAsync(transform))
        {
            Debug.LogError($"Couldn't load {path}");
            return;
        }

        Bounds? bounds = null;
        foreach (var mf in GetComponentsInChildren<MeshFilter>())
        {
            mf.gameObject.AddComponent<MeshCollider>().sharedMesh = mf.sharedMesh;
            var b = mf.GetComponent<Renderer>().bounds;
            if (bounds is Bounds all) { all.Encapsulate(b); bounds = all; } else bounds = b;
        }
        if (bounds is not Bounds room)
        {
            Debug.LogError($"{path} has no meshes");
            return;
        }
        Debug.Log($"Loaded {Path.GetFileName(path)}: {room.size.x:F1} x {room.size.z:F1} m, {room.size.y:F1} m high");

        if (player != null)
        {
            // floor is at y = 0 (export_unity.py levels it); start 1 m up and let gravity settle the player
            player.transform.position = new Vector3(room.center.x, 1f, room.center.z);
            player.SetActive(true);
        }
    }

    static string CommandLinePath()
    {
        var args = Environment.GetCommandLineArgs();
        int i = Array.IndexOf(args, "-scan");
        return i >= 0 && i + 1 < args.Length ? args[i + 1] : null;
    }
}
