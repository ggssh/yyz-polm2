#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <netinet/in.h> // For socket related structs.
#include <netdb.h>      // For gethostbyname.
#include <unistd.h>     // For read, write, close, open.

#include "jni.h"
#include "jvmti.h"

#define DUMPER_PORT 9999

#define ROOTS 0

// Note: I found out that heap dumps contain roots information. This means that
// there is no need to trace roots here!
#if ROOTS
#define ROOTS_FILE "roots"
#define HEAP_ROOT_TAG 1
#endif

/* Global static data */
static jvmtiEnv* jvmti;
static jvmtiCapabilities capabilities;
static jvmtiEventCallbacks callbacks;
static FILE* log;
static pid_t pid;
#if ROOTS
static FILE* roots;
static int root_counter;
static int stack_counter;
static int gc_counter;
jrawMonitorID lock;
#endif

// Function that facilitates error handling.
static void check_jvmti_error(jvmtiEnv *jvmti_env, jvmtiError errnum, const char *str) {
    if (errnum != JVMTI_ERROR_NONE) {
        char *errnum_str = NULL;                      
        (void) jvmti_env->GetErrorName(errnum, &errnum_str);
        fprintf(log,
                "ERROR: JVMTI: %d(%s): %s\n", 
                errnum,
                (errnum_str == NULL ? "Unknown" : errnum_str),
                (str == NULL ? "" : str));
        }                                                                       
}

// Send snapshot request to coordinator.
static int send_snapshot_request(jvmtiEnv* jvmti_env) {
    int sockfd;
    struct sockaddr_in serv_addr;
    struct hostent *server;

    sockfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sockfd < 0) {
            fprintf(log, "ERROR: JVMTI: Unable to open socket.\n");
            return -1;
    }

    server = gethostbyname("localhost");
    if (server == NULL) {
            fprintf(log, "ERROR: JVMTI: Unable to get host by name (localhost).\n");
            return -1;
    }

    bzero((char *) &serv_addr, sizeof (serv_addr));
    serv_addr.sin_family = AF_INET;
    bcopy((char *) server->h_addr,
          (char *) &serv_addr.sin_addr.s_addr,
          server->h_length);
    serv_addr.sin_port = htons(DUMPER_PORT);

    if (connect(sockfd, (struct sockaddr *) &serv_addr, sizeof(serv_addr)) < 0) {
            fprintf(log, "ERROR: JVMTI: Unable to connect to dumper.\n");
            return -1;
    }
    
    if (write(sockfd, &pid, sizeof(pid_t)) < 0) {
        fprintf(log, "ERROR: JVMTI: Unable to send pid (%u) to dumper.\n", pid);
    }

    jvmti->SendFreeRegions(sockfd);

    close(sockfd);
}

#if ROOTS
// Enter a critical section by doing a JVMTI Raw Monitor Enter 
static void enter_critical_section(jvmtiEnv *jvmti) {                           
        jvmtiError error;                                                       
                                                                                
        error = jvmti->RawMonitorEnter(lock);                            
        check_jvmti_error(jvmti, error, "Cannot enter with raw monitor");          
}                                                                               
                                                                                
// Exit a critical section by doing a JVMTI Raw Monitor Exit
static void exit_critical_section(jvmtiEnv *jvmti) {                            
        jvmtiError error;                                                       
                                                                                
        error = jvmti->RawMonitorExit(lock);                             
        check_jvmti_error(jvmti, error, "Cannot exit with raw monitor");        
} 

static jvmtiIterationControl JNICALL                                            
heap_root_callback(jvmtiHeapRootKind  root_kind,                                
                jlong class_tag, jlong size, jlong* tag_ptr,                    
                void *user_data) {
    root_counter++;
    *tag_ptr = HEAP_ROOT_TAG;
    return JVMTI_ITERATION_CONTINUE;                                                                            
}

// Callback
static jvmtiIterationControl JNICALL
stack_ref_callback(jvmtiHeapRootKind  root_kind,
                jlong class_tag, jlong size, jlong* tag_ptr,
                jlong thread_tag, jint depth,
                jmethodID method,                               
                jint slot, void *user_data) {
    stack_counter++;
    *tag_ptr = HEAP_ROOT_TAG;
    return JVMTI_ITERATION_CONTINUE;                                                                            
}

// Worker thread that iterates through heap roots.                                                                          
static void JNICALL                                                             
roots_worker(jvmtiEnv* jvmti_env, JNIEnv* jni, void *p) {
    jvmtiError err;
    jint tag_count = 1;
    const jlong tags = HEAP_ROOT_TAG;
    jint count_ptr;
    jobject* object_result_ptr = NULL;
    jlong* tag_result_ptr =NULL;
    jint obj_hash;

    while (1) {
        err = jvmti_env->RawMonitorEnter(lock);
        check_jvmti_error(jvmti_env, err, "raw monitor enter");
        err = jvmti_env->RawMonitorWait(lock, 0);
        check_jvmti_error(jvmti_env, err, "raw monitor wait");

        // TODO - use a condition to break the cycle
        
        // Reset counter, launch heap root iteration.
        root_counter = 0;
        stack_counter = 0;
        err = jvmti_env->IterateOverReachableObjects(&heap_root_callback, &stack_ref_callback, NULL, NULL);
        check_jvmti_error(jvmti_env, err, "IterateOverReachableObjects failed.");

        fprintf(log, "IterateOverReachableObjects tagged %d roots and %d stacks!\n", root_counter, stack_counter);

        err = jvmti_env->GetObjectsWithTags(
            tag_count,
            &tags,
            &count_ptr,
            &object_result_ptr,
            &tag_result_ptr);
        check_jvmti_error(jvmti_env, err, "GetObjectsWithTags failed.");

        fprintf(log, "GetObjectsWithTags returned %d!\n", count_ptr);

        for (int i = 0; i < count_ptr; i++) {
            err = jvmti_env->GetObjectHashCode(object_result_ptr[i], &obj_hash);
            check_jvmti_error(jvmti_env, err, "GetObjectHashCode failed.");
            // Delete local ref (not doing this would increase the number of roots).
            jni->DeleteLocalRef(object_result_ptr[i]);
            fprintf(roots, "%d %d\n", gc_counter, obj_hash);
        }

        if (object_result_ptr != NULL) {
            err = jvmti_env->Deallocate((unsigned char*)object_result_ptr);
            check_jvmti_error(jvmti_env, err, "Deallocate tagged objects failed.");
        }
        if (tag_result_ptr != NULL) {
            err = jvmti_env->Deallocate((unsigned char*)tag_result_ptr);
            check_jvmti_error(jvmti_env, err, "Deallocate object tags failed.");
        }
       
        err = jvmti_env->RawMonitorExit(lock);
        check_jvmti_error(jvmti_env, err, "raw monitor exit");

        fflush(log);
    }
}

// Creates a java.lang.Thread.                                                                        
static jthread alloc_thread(JNIEnv *env) {
        jclass thrClass;
        jmethodID cid;
        jthread res;

        thrClass = env->FindClass("java/lang/Thread");
        if (thrClass == NULL) {
                fprintf(stderr, "ERROR: JVMTI: Unable to find java.lang.Thread.\n");
                return NULL;
        }
        cid = env->GetMethodID(thrClass, "<init>", "()V");
        if (cid == NULL) {
            fprintf(stderr, "ERROR: JVMTI: Unable to find Thread constructor method.\n");
            return NULL;
        }
        res = env->NewObject(thrClass, cid);
        if (res == NULL) {
                fprintf(stderr, "ERROR: JVMTI: Unable to create new Thread object.\n");
            return NULL;
        }
        return res;
}
#endif

// Callback for JVMTI_EVENT_GARBAGE_COLLECTION_START
static void JNICALL 
gc_start(jvmtiEnv* jvmti_env) {
    jvmtiError err;
    fprintf(log, "GarbageCollectionStart\n");

#if ROOTS
    // Notify heap root worker to enumerate roots.
    err = jvmti_env->RawMonitorEnter(lock);
    check_jvmti_error(jvmti_env, err, "raw monitor enter");
    gc_counter++;
    err = jvmti_env->RawMonitorNotify(lock);
    check_jvmti_error(jvmti_env, err, "raw monitor notify");
    err = jvmti_env->RawMonitorExit(lock);
    check_jvmti_error(jvmti_env, err, "raw monitor exit");
#endif
}

// Callback for JVMTI_EVENT_GARBAGE_COLLECTION_FINISH
static void JNICALL 
gc_finish(jvmtiEnv* jvmti_env) {
    int ret = send_snapshot_request(jvmti_env);
    fprintf(log, "Send snapshot request returned %d\n", ret);
    // TODO - if cloned, wait for something?
    fprintf(log, "GarbageCollectionFinish\n");
    fflush(log);
    
}

// Callback for JVMTI_EVENT_VM_INIT. Add callbacks and notification events for
// GC_START and GC_FINISH. Launch worker thread.
static void JNICALL 
vm_init(jvmtiEnv *jvmti_env, JNIEnv *env, jthread thread) {
    jvmtiError err;
    fprintf(log, "VM Init\n");
    
    callbacks.GarbageCollectionStart  = &gc_start;
    callbacks.GarbageCollectionFinish = &gc_finish;
    
    err = jvmti_env->SetEventCallbacks(&callbacks, sizeof(callbacks));
    check_jvmti_error(jvmti_env, err, "SetEventCallbacks failed.");
    
    err = jvmti_env->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_GARBAGE_COLLECTION_START, NULL);
    check_jvmti_error(jvmti_env, err, "Unable to set event notification for GC Start.");
    
    err = jvmti_env->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_GARBAGE_COLLECTION_FINISH, NULL);
    check_jvmti_error(jvmti_env, err, "Unable to set event notification for GC Finish.");

#if ROOTS    
    err = jvmti_env->RunAgentThread(alloc_thread(env), &roots_worker, NULL, JVMTI_THREAD_MAX_PRIORITY);                             
    check_jvmti_error(jvmti_env, err, "Unable to create agent thread.");
#endif
}

/* Agent_OnLoad() is called first, we prepare for a VM_INIT event here. */
JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    jint                rc;
    jvmtiError          err;

    // Note: we are using options to carry the name of the log.
    if((log = fopen(options, "w")) == NULL) {
        fprintf(stderr, "ERROR: JVMTI: Unable to open log file.\n");
    }

    // Get JVMTI environment.
    rc = vm->GetEnv((void **)&jvmti, JVMTI_VERSION);
    if (rc != JNI_OK) {
	fprintf(log, "ERROR: JVMTI: Unable to create jvmtiEnv, GetEnv failed, error=%d\n", rc);
	return -1;
    }

    // Get and Add JVMTI capabilities.
    err = jvmti->GetCapabilities(&capabilities);
    check_jvmti_error(jvmti, err, "GetCapabilities failed.");

#if ROOTS
    // Needed for heap roots iteration.
    capabilities.can_tag_objects = 1;
#endif

    // Needed to receive GC events.
    capabilities.can_generate_garbage_collection_events = 1;
    
    err = jvmti->AddCapabilities(&capabilities);
    check_jvmti_error(jvmti, err, "AddCapabilities failed.");

    // Set callbacks and enable event notifications for VM_INIT.
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.VMInit = &vm_init;
    
    err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
    check_jvmti_error(jvmti, err, "SetEventCallbacks failed.");
    
    err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, NULL);
    check_jvmti_error(jvmti, err, "SetEventNotificationMode failed.");

#if ROOTS    
    err = jvmti->CreateRawMonitor("agent data", &lock);          
    check_jvmti_error(jvmti, err, "Cannot create raw monitor"); 
#endif

    // Discover pid (important to create snapshots).
    pid = getpid();

#if ROOTS
    if((roots = fopen(ROOTS_FILE, "w")) == NULL) {
        fprintf(stderr, "ERROR: JVMTI: Unable to open roots file.\n");
    }
#endif
    return 0;
}

/* Agent_OnUnload() is called last */
JNIEXPORT void JNICALL
Agent_OnUnload(JavaVM *vm) {
    fprintf(log, "Unloading Agent\n");
    fclose(log);
#if ROOTS
    fclose(roots);
#endif
}
