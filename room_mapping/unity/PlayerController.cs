using UnityEngine;
using UnityEngine.InputSystem;

// Walk through a scanned room in first or third person.
// WASD move, mouse look, Shift run, V switch first/third person, Esc frees the mouse (click to grab it again).
[RequireComponent(typeof(CharacterController))]
public class PlayerController : MonoBehaviour
{
    public Transform cam;
    public float walkSpeed = 1.4f, runSpeed = 3f, lookSpeed = 0.1f, eyeHeight = 1.6f;
    [Tooltip("Camera offset from the eye in third person, in the look direction")]
    public Vector3 thirdPersonOffset = new Vector3(0f, 0.4f, -2.5f);

    CharacterController cc;
    Vector3 spawn;
    float pitch, fallSpeed;
    bool thirdPerson;

    void Reset()  // sensible person-sized capsule when the component is added
    {
        var c = GetComponent<CharacterController>();
        c.height = 1.8f;
        c.radius = 0.25f;
        c.center = new Vector3(0f, 0.9f, 0f);
        c.stepOffset = 0.3f;
    }

    void OnEnable()
    {
        cc = GetComponent<CharacterController>();
        spawn = transform.position;
        Cursor.lockState = CursorLockMode.Locked;
    }

    void Update()
    {
        var kb = Keyboard.current;
        var mouse = Mouse.current;
        if (kb == null || mouse == null) return;

        if (kb.escapeKey.wasPressedThisFrame) Cursor.lockState = CursorLockMode.None;
        if (mouse.leftButton.wasPressedThisFrame) Cursor.lockState = CursorLockMode.Locked;
        if (kb.vKey.wasPressedThisFrame) thirdPerson = !thirdPerson;

        if (Cursor.lockState == CursorLockMode.Locked)
        {
            Vector2 d = mouse.delta.ReadValue() * lookSpeed;
            transform.Rotate(0f, d.x, 0f);
            pitch = Mathf.Clamp(pitch - d.y, -85f, 85f);
        }

        var input = new Vector3((kb.dKey.isPressed ? 1 : 0) - (kb.aKey.isPressed ? 1 : 0), 0f,
                                (kb.wKey.isPressed ? 1 : 0) - (kb.sKey.isPressed ? 1 : 0));
        float speed = kb.leftShiftKey.isPressed ? runSpeed : walkSpeed;
        fallSpeed = cc.isGrounded ? -1f : fallSpeed + Physics.gravity.y * Time.deltaTime;
        cc.Move((transform.TransformDirection(Vector3.ClampMagnitude(input, 1f)) * speed + Vector3.up * fallSpeed)
                * Time.deltaTime);

        if (transform.position.y < -10f)  // fell through a hole in the scan
        {
            cc.enabled = false;
            transform.position = spawn;
            cc.enabled = true;
            fallSpeed = 0f;
        }
    }

    void LateUpdate()
    {
        if (cam == null) return;
        var eye = transform.position + Vector3.up * eyeHeight;
        var look = Quaternion.Euler(pitch, transform.eulerAngles.y, 0f);
        var pos = eye;
        if (thirdPerson)
        {
            var back = look * thirdPersonOffset;
            // pull the camera in front of walls so they don't block the view
            pos = Physics.SphereCast(eye, 0.15f, back.normalized, out var hit, back.magnitude)
                ? eye + back.normalized * hit.distance
                : eye + back;
        }
        cam.SetPositionAndRotation(pos, look);
    }
}
