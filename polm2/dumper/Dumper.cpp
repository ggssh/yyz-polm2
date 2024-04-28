#include <stdio.h>
#include <stdlib.h>     // Needed for exit, getenv.
#include <unistd.h>     // Needed for fork, execl, etc.
#include <string.h>     // Needed for bzero.
#include <sys/wait.h>   // Needed for wait.
#include <sys/socket.h> // Needed for sockets.
#include <netdb.h>      // Needed for sockets.
#include <sys/stat.h>   // Needed for mkdir.
#include <sys/types.h>  // Needed for mkdir.
#include <errno.h>      // Needed for errno.
#include <sys/time.h>   // Needed for clock.
#include <inttypes.h>   // Needed for INTPTR_FORMAT

#define INTPTR_FORMAT "0x%016" PRIxPTR
#define DUMPER_PORT 9999
char* criu;

int dump_jvm(pid_t dumpee, char* prev_dump_dir, char* dump_dir, char* dump_log) {
    pid_t dumper;
    char pid[8];
    struct timeval start, end;
    
    gettimeofday(&start, NULL);
    
    sprintf(pid, "%lu", (unsigned long) dumpee);

    dumper = fork();
    if(dumper == 0) {
        // TODO - this is horrible! The only difference is the prev dump dir ref!
        if (prev_dump_dir == NULL) {
            execl(criu, criu, 
            "dump",
            "--images-dir", dump_dir, 
            "-d", 
            "-vvvv", 
            "-o", dump_log, 
            "-t", pid,
            "--track-mem",
            "--leave-running",
            "--shell-job", // TODO - needed?
            "--file-locks",// TODO - needed?
            (char*) NULL);
        } else {
            execl(criu, criu, 
            "dump",
            "--images-dir", dump_dir, 
            "-d", 
            "-vvvv", 
            "-o", dump_log, 
            "-t", pid,
            "--prev-images-dir", prev_dump_dir,
            "--track-mem",
            "--leave-running",
            "--shell-job", // TODO - needed?
            "--file-locks",// TODO - needed?
            (char*) NULL);
        }
        
    }
    else {
        printf("Launched criu dump (pid = %lu). See %s.\n", 
            (unsigned long) dumper, dump_log);

        if (waitpid(dumper, NULL, 0) < 0) {
            perror("Failed to wait for dump to finish.\n");
            exit(-1);
        }

        gettimeofday(&end, NULL);
        printf("Finished dump (pid = %lu) in %ld microseconds.\n", 
            (unsigned long) dumper,
            ((end.tv_sec - start.tv_sec) * 1000000 - end.tv_usec - start.tv_usec));
        return 0;
    }
}

int prepare_server_socket() {
    struct sockaddr_in serv_addr;
    int sockopt = 1;
        
    int sockfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sockfd < 0) {
        perror("ERROR: Unable to open agent socket.\n");
        exit(-1);
    }

    bzero((char *) &serv_addr, sizeof(serv_addr));
    serv_addr.sin_family = AF_INET;
    serv_addr.sin_addr.s_addr = INADDR_ANY;
    serv_addr.sin_port = htons(DUMPER_PORT);

    if (setsockopt(
            sockfd, 
            SOL_SOCKET, 
            SO_REUSEADDR, 
            &sockopt, 
            sizeof(sockopt)) == -1) {
        perror("ERROR: Unable to set SO_REUSEADDR.\n");
        exit(-1);
    }

    if (bind(sockfd, (struct sockaddr *) &serv_addr, sizeof(serv_addr)) < 0) { 
        perror("ERROR: Unable to bind agent socket.\n");
        exit(-1);
    }

    if (listen(sockfd, 1)) {
        perror("ERROR: Unable to listen image socket.\n");
        exit(-1);
    }

    return sockfd;
}

void prepare_directory(char* dir) {
    if (mkdir(dir, 0777)) {
        if (errno != EEXIST) {
            perror("ERROR: Unable to create dir.");
            exit(-1);
        }
    }
}

int main(int argc, char** argv) {
    int sockfd, clientfd, n, max_dumps = 0;
    struct sockaddr_in cli_addr;
    socklen_t clilen = sizeof(cli_addr);
    char dump_dir[256], prev_dump_dir[256], dump_log[256];
    char *output_dir, *log_dir;
    pid_t pid;
    uint64_t top_address, end_address;

    // Process args.
    if (argc != 5) {
        fprintf(stderr, "ERROR: syntax: Dumper <criu path> <max dumps> <output dir> <log dir>.\n");
        return -1;
    }

    criu = argv[1];    
    max_dumps = atoi(argv[2]);
    output_dir = argv[3];
    log_dir = argv[4];
    
    // Prepare server socket
    sockfd = prepare_server_socket();
    
    printf("Waiting for dump requests on %d\n", DUMPER_PORT);

    // For eternity... wait for connection, dump process.
    for (int dump_counter = 0; ; dump_counter++) {

        if (max_dumps != 0 && dump_counter == max_dumps) {
            break;
        }

        clientfd = accept(sockfd, (struct sockaddr *) &cli_addr, &clilen);
        if (clientfd < 0) {
            fprintf(stderr, "ERROR: connection accept failed.\n");
            return -1;
        }

        n = read(clientfd,&pid,sizeof(pid));
        if (n <= 0) { 
            fprintf(stderr,"ERROR: reading pid from socket (returned %d).\n", n);
            return -1;
        }

        printf("Request to dump pid=%u...\n", pid);


        for (;;) {
            if ((n = read(clientfd, &top_address, sizeof(uint64_t))) != sizeof(uint64_t)) {
                if (n == 0) {
                    break;
                } else {
                    fprintf(stderr,"ERROR: reading top address from socket (returned %d).\n", n);
                }
            }

            if ((n = read(clientfd, &end_address, sizeof(uint64_t))) != sizeof(uint64_t)) {
                fprintf(stderr,"ERROR: reading end address from socket (returned %d).\n", n);
            }
            printf("Received free region top="INTPTR_FORMAT" end="INTPTR_FORMAT"\n", top_address, end_address);
        }

        if(n > 0) {
            fprintf(stderr, "ERROR: agent should have just closed the connection.\n");
            return -1;
        }

        close(clientfd);

        sprintf(dump_dir, "%s/dump-%u-%d", output_dir, pid, dump_counter);
        prepare_directory(dump_dir);

        sprintf(dump_log, "%s/dump-%u-%d.log", log_dir, pid, dump_counter);

        if (dump_counter > 0) {
            sprintf(prev_dump_dir, "%s/dump-%u-%d", output_dir, pid, dump_counter - 1);
            prepare_directory(prev_dump_dir);
        }

        n = dump_jvm(pid, dump_counter == 0 ? NULL : prev_dump_dir, dump_dir, dump_log);

        if (n < 0) {
            fprintf(stderr, "ERROR: failed to dump jvm (error code = %d).\n", n);
            return -1;
        }

        printf("Request to dump pid=%u...Done\n", pid);
    }
    
    return 0;
}

